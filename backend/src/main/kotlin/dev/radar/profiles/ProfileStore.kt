package dev.radar.profiles

import dev.radar.desktop.RuntimePaths
import dev.radar.desktop.SessionLease
import dev.radar.telegram.ApiCredentials
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.sqlite.SQLiteConnection
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID

@Serializable
data class ProfileIdentity(val userId: Long, val name: String, val username: String? = null, val avatarAvailable: Boolean = false)

@Serializable
data class TelegramProfile(
    val id: String,
    val identity: ProfileIdentity? = null,
    val legacy: Boolean = false,
    val confirmed: Boolean = false,
    val loggedOut: Boolean = false,
)

@Serializable
private data class Registry(val version: Int = 1, val activeId: String, val profiles: List<TelegramProfile>)
@Serializable
private data class Migration(
    val id: String,
    val external: Boolean = false,
    val originals: Map<String, String?>? = null,
    val backupFingerprint: String? = null,
)

/** Owner-only local metadata. Call prepare before constructing a client or opening a workspace. */
class ProfileStore(home: Path = Path.of(System.getProperty("user.home"))) {
    private val home = home.toRealPath()
    private val root = this.home.resolve(".hradar")
    private val profiles = root.resolve("profiles")
    private val registry = root.resolve("profiles.json")
    private val manifest = root.resolve("profile-migration.json")
    private val receipt = root.resolve("current-profile-migrated.json")
    private val json = Json { encodeDefaults = true }

    @Synchronized fun prepare() {
        directory(root)
        directory(profiles)
        if (!exists(registry)) {
            val completed = exists(receipt) || exists(root.resolve("external-profile-imported.json"))
            val orphaned = Files.list(profiles).use { it.findAny().isPresent }
            check(!completed && (!orphaned || exists(manifest))) { "Profile registry is missing; restore it from backup instead of recreating retained sessions" }
        }
        migrateSharedCredentials()
        for (database in listOf(root.resolve("data/research.sqlite"), root.resolve("research.sqlite"))) {
            check(exists(database) || listOf("-wal", "-shm").none { exists(Path.of("$database$it")) }) { "Orphan SQLite sidecars; restore the original database before migration" }
        }
        if (exists(manifest)) finishMigration(read<Migration>(manifest))
        if (exists(registry)) {
            validate(load())
            if (exists(receipt)) verifyRetainedOriginals(read<Migration>(receipt))
            return
        }
        val current = sources(false)
        if (current.any { exists(it.second) }) {
            val migration = Migration(UUID.randomUUID().toString())
            atomic(manifest, json.encodeToString(Migration.serializer(), migration))
            finishMigration(migration)
        } else {
            val first = TelegramProfile(UUID.randomUUID().toString())
            RuntimePaths(home, first.id).prepare()
            save(Registry(activeId = first.id, profiles = listOf(first)))
        }
    }

    @Synchronized fun list(): List<TelegramProfile> = load().profiles
    @Synchronized fun active(): TelegramProfile = load().let { state -> state.profiles.single { it.id == state.activeId } }
    @Synchronized fun create(): TelegramProfile {
        val state = load()
        val profile = TelegramProfile(UUID.randomUUID().toString())
        RuntimePaths(home, profile.id).prepare()
        save(state.copy(profiles = state.profiles + profile))
        return profile
    }
    @Synchronized fun select(id: String) { val state = load(); requireProfile(state, id); save(state.copy(activeId = id)) }
    @Synchronized fun bind(id: String, identity: ProfileIdentity, existingSession: Boolean): TelegramProfile {
        require(identity.userId > 0) { "Invalid Telegram user ID" }
        val state = load()
        val profile = requireProfile(state, id)
        check(profile.identity == null || profile.identity.userId == identity.userId) { "Telegram account does not match this profile" }
        check(state.profiles.none { it.id != id && it.identity?.userId == identity.userId }) { "Telegram account already belongs to another profile" }
        check(!profile.legacy || profile.confirmed || profile.identity?.userId == identity.userId || (existingSession && !profile.loggedOut)) {
            "Legacy workspace requires its retained authorized session; a new login cannot claim old data"
        }
        return update(state, profile.copy(identity = identity, confirmed = !profile.legacy || profile.confirmed, loggedOut = false))
    }
    @Synchronized fun confirmLegacy(id: String, expectedUserId: Long): TelegramProfile {
        val state = load(); val profile = requireProfile(state, id)
        check(profile.legacy && profile.identity?.userId == expectedUserId && !profile.loggedOut) { "Legacy identity must be verified before confirmation" }
        return update(state, profile.copy(confirmed = true))
    }
    @Synchronized fun markLoggedOut(id: String) { val state = load(); update(state, requireProfile(state, id).copy(loggedOut = true)) }
    @Synchronized fun legacyAvailable(): Boolean = exists(home.resolve(".recruitment-radar/tdlib")) &&
        !exists(root.resolve("external-profile-imported.json"))
    @Synchronized fun importLegacy(): TelegramProfile {
        check(legacyAvailable()) { "No legacy Telegram session is available" }
        val migration = Migration(UUID.randomUUID().toString(), external = true)
        atomic(manifest, json.encodeToString(Migration.serializer(), migration))
        finishMigration(migration)
        return list().single { it.id == migration.id }
    }

    private fun migrateSharedCredentials() {
        val old = root.resolve("telegram-api.json")
        val current = root.resolve("config/telegram-api.json")
        if (!exists(old) || exists(current)) return
        safe(old)
        val value = json.decodeFromString<ApiCredentials>(Files.readString(old)).also { it.validate() }
        atomic(current, json.encodeToString(ApiCredentials.serializer(), value))
    }

    private fun update(state: Registry, profile: TelegramProfile): TelegramProfile {
        save(state.copy(profiles = state.profiles.map { if (it.id == profile.id) profile else it })); return profile
    }
    private fun requireProfile(state: Registry, id: String): TelegramProfile {
        validId(id); return state.profiles.singleOrNull { it.id == id } ?: error("Unknown profile")
    }
    private fun load(): Registry = read<Registry>(registry).also(::validate)
    private fun validate(state: Registry) {
        check(state.version == 1 && state.profiles.isNotEmpty()) { "Unsupported profile registry" }
        state.profiles.forEach { validId(it.id) }
        check(state.profiles.map { it.id }.distinct().size == state.profiles.size)
        check(state.profiles.count { it.id == state.activeId } == 1)
        val ids = state.profiles.mapNotNull { it.identity?.userId }
        check(ids.all { it > 0 } && ids.distinct().size == ids.size) { "Duplicate Telegram identities" }
        state.profiles.forEach { check(Files.isDirectory(profiles.resolve(it.id), LinkOption.NOFOLLOW_LINKS)) { "Profile workspace missing; restore its backup" }; safe(profiles.resolve(it.id)) }
    }
    private fun save(state: Registry) { validate(state); atomic(registry, json.encodeToString(Registry.serializer(), state)) }
    private inline fun <reified T> read(path: Path): T { safe(path); check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)); return json.decodeFromString(Files.readString(path)) }
    private fun validId(id: String) { require(UUID.fromString(id).toString() == id) { "Invalid profile ID" } }

    private fun sources(external: Boolean): List<Pair<String, Path>> = if (external)
        listOf("tdlib" to home.resolve(".recruitment-radar/tdlib"))
    else listOf(
        "data/research.sqlite" to if (exists(root.resolve("data/research.sqlite"))) root.resolve("data/research.sqlite") else root.resolve("research.sqlite"),
        "tdlib" to root.resolve("tdlib"), "documents" to root.resolve("documents"),
        "config/telegram-bot.json" to if (exists(root.resolve("config/telegram-bot.json"))) root.resolve("config/telegram-bot.json") else root.resolve("telegram-bot.json"),
    )

    private fun finishMigration(migration: Migration) {
        validId(migration.id)
        val source = sources(migration.external).filter { exists(it.second) }
        check(source.isNotEmpty()) { "Migration originals are missing; restore the retained originals" }
        source.forEach { safeTree(it.second) }
        val session = source.singleOrNull { it.first == "tdlib" }?.second
        val lease = session?.let { SessionLease.acquire(it) ?: error("Telegram session is in use; stop the other application") }
        try {
            assertOffline(source.map { it.second }, session?.resolve("hradar.lock"))
            val backup = root.resolve("backups/profile-${migration.id}")
            val target = profiles.resolve(migration.id)
            directory(backup.parent)
            // SHM is coordination state, not committed content. Guard it with lsof;
            // fingerprint DB/WAL and perform SQLite reads on a private closed-file copy.
            val checked = source.map { it.second }.flatMap { path ->
                if (path.fileName.toString().endsWith(".sqlite")) listOf(path, Path.of("$path-wal")) else listOf(path)
            }
            val before = checked.associate { it.toString() to if (exists(it)) fingerprint(it) else null }
            check(migration.originals == null || migration.originals == before) { "Originals changed since migration began; manual recovery is required" }
            var progress = migration.copy(originals = before)
            atomic(manifest, json.encodeToString(Migration.serializer(), progress))
            if (!exists(backup)) {
                val footprint = checked.filter(::exists).fold(0L) { total, path -> Math.addExact(total, bytes(path)) }
                val required = Math.multiplyExact(footprint, 3L)
                check(Files.getFileStore(root).usableSpace >= required) { "Insufficient space for private input, backup and profile publication" }
                val stage = backup.resolveSibling("${backup.fileName}.staging")
                removeStage(stage); directory(stage)
                source.forEach { (name, path) ->
                    val destination = stage.resolve(name)
                    if (name == "data/research.sqlite") snapshot(path, destination) else copy(path, destination)
                }
                assertOffline(source.map { it.second }, session?.resolve("hradar.lock"))
                check(checked.all { before[it.toString()] == if (exists(it)) fingerprint(it) else null }) { "Original data changed during migration" }
                val digest = fingerprint(stage)
                check(progress.backupFingerprint == null || progress.backupFingerprint == digest) { "Rebuilt backup differs from migration manifest" }
                progress = progress.copy(backupFingerprint = digest)
                atomic(manifest, json.encodeToString(Migration.serializer(), progress))
                Files.move(stage, backup, StandardCopyOption.ATOMIC_MOVE)
            }
            safeTree(backup)
            val backupDigest = fingerprint(backup)
            check(progress.backupFingerprint == null || progress.backupFingerprint == backupDigest) { "Migration backup changed; manual recovery is required" }
            if (progress.backupFingerprint == null) {
                progress = progress.copy(backupFingerprint = backupDigest)
                atomic(manifest, json.encodeToString(Migration.serializer(), progress))
            }
            if (!exists(target)) {
                val stage = profiles.resolve("${migration.id}.staging")
                removeStage(stage); copy(backup, stage)
                Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE)
            }
            verifyPublication(backup, target)
            RuntimePaths(home, migration.id).prepare()
            val profile = TelegramProfile(migration.id, legacy = true)
            val state = if (exists(registry)) load() else Registry(activeId = profile.id, profiles = listOf(profile))
            if (state.profiles.none { it.id == profile.id }) save(state.copy(profiles = state.profiles + profile)) else save(state)
            val completed = if (migration.external) root.resolve("external-profile-imported.json") else receipt
            atomic(completed, json.encodeToString(Migration.serializer(), progress))
            Files.delete(manifest)
        } finally { lease?.close() }
    }

    private fun verifyRetainedOriginals(completed: Migration) {
        check(!completed.external && completed.originals != null && completed.backupFingerprint != null) { "Invalid completed migration receipt" }
        validId(completed.id)
        val source = sources(false).filter { exists(it.second) }.map { it.second }
        source.forEach(::safeTree)
        val session = source.singleOrNull { it == root.resolve("tdlib") }
        val lease = session?.let { SessionLease.acquire(it) ?: error("Retained original Telegram session is in use") }
        try {
            assertOffline(source, session?.resolve("hradar.lock"))
            val checked = source.flatMap { path ->
                if (path.fileName.toString().endsWith(".sqlite")) listOf(path, Path.of("$path-wal")) else listOf(path)
            }
            val actual = checked.associate { it.toString() to if (exists(it)) fingerprint(it) else null }
            check(completed.originals == actual) {
                "Retained originals changed after migration, possibly by an older hradar; stop and reconcile the copies before continuing"
            }
        } finally { lease?.close() }
    }

    private fun bytes(path: Path): Long {
        safeTree(path)
        return Files.walk(path).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()
                .fold(0L) { total, file -> Math.addExact(total, Files.size(file)) }
        }
    }

    private fun verifyPublication(backup: Path, target: Path) {
        safeTree(target)
        val expected = Files.walk(backup).use { paths -> paths.filter { Files.isRegularFile(it) }.map { backup.relativize(it) }.toList().toSet() }
        val actual = Files.walk(target).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString() != "hradar.lock" }.map { target.relativize(it) }.toList().toSet() }
        check(expected == actual && expected.all { Files.mismatch(backup.resolve(it), target.resolve(it)) == -1L }) {
            "Published profile differs from its retained backup; manual recovery is required"
        }
    }

    private fun snapshot(source: Path, target: Path) {
        safe(source); directory(target.parent)
        // Even mode=ro can create empty WAL/SHM beside a WAL-mode database. Never
        // let JDBC touch retained originals: all source handles are closed and guarded
        // before copying DB+WAL, then the native backup resolves committed WAL pages.
        val input = Files.createTempDirectory(target.parent, ".sqlite-snapshot-", PosixFilePermissions.asFileAttribute(DIR_MODE))
        try {
            val inputDatabase = input.resolve("research.sqlite")
            copy(source, inputDatabase)
            val wal = Path.of("$source-wal")
            if (exists(wal)) copy(wal, Path.of("$inputDatabase-wal"))
            DriverManager.getConnection("jdbc:sqlite:${inputDatabase.toUri()}?mode=ro").use { connection ->
                check((connection as SQLiteConnection).database.backup("main", target.toString(), null) == 0) { "SQLite backup failed" }
            }
        } finally { removeStage(input) }
        fileMode(target)
        DriverManager.getConnection("jdbc:sqlite:${target.toUri()}?mode=rw").use { connection ->
            connection.createStatement().use { sql ->
                // Normalize the completed backup to a standalone DB before publication.
                check(sql.executeQuery("PRAGMA journal_mode=DELETE").use { it.next(); it.getString(1).equals("delete", true) }) { "SQLite backup journal normalization failed" }
                val version = sql.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
                check(version in 1..14) { "Unknown SQLite schema; original data remains quarantined" }
                check(sql.executeQuery("PRAGMA integrity_check").use { it.next(); it.getString(1) } == "ok") { "SQLite integrity check failed" }
                check(!sql.executeQuery("PRAGMA foreign_key_check").use { it.next() }) { "SQLite foreign key check failed" }
                val required = if (version == 14) setOf("monitored_channels", "search_templates", "documents", "document_fts", "owner_inbox") else setOf("monitored_channels", "search_templates")
                val tables = sql.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
                check(tables.containsAll(required)) { "Unrecognized SQLite workspace" }
            }
        }
        FileChannel.open(target, StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun assertOffline(paths: List<Path>, allowedLock: Path?) {
        for (path in paths) {
            val command = if (Files.isDirectory(path)) listOf("lsof", "-Fpn", "+D", path.toString())
                else listOf("lsof", "-Fpn", "--") + listOf(path, Path.of("$path-wal"), Path.of("$path-shm")).filter { exists(it) }.map { it.toString() }
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            var pid = -1L
            val busy = output.lineSequence().any { line ->
                if (line.startsWith("p")) pid = line.drop(1).toLongOrNull() ?: -1
                line.startsWith("n") && !(pid == ProcessHandle.current().pid() && line.drop(1) == allowedLock?.toRealPath()?.toString())
            }
            check(exit in 0..1 && !busy && output.lineSequence().all { it.isBlank() || it[0] in "pfn" }) { "Workspace files are open; stop the application before migration (keep logs outside the workspace)" }
        }
    }

    private fun safe(path: Path) {
        var current: Path? = path.toAbsolutePath()
        while (current != null) { check(!Files.isSymbolicLink(current)) { "Symlinks are not allowed in profile storage" }; current = current.parent }
    }
    private fun safeTree(path: Path) {
        safe(path)
        Files.walk(path).use { paths -> paths.forEach { check(!Files.isSymbolicLink(it) && (Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS))) { "Unsupported file in profile storage" } } }
    }
    private fun directory(path: Path) {
        safe(path)
        val missing = generateSequence(path) { it.parent }.takeWhile { !exists(it) }.toList().reversed()
        for (entry in missing) Files.createDirectory(entry, PosixFilePermissions.asFileAttribute(DIR_MODE))
        check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)); Files.setPosixFilePermissions(path, DIR_MODE)
    }
    private fun copy(source: Path, target: Path) {
        safeTree(source)
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            directory(target)
            Files.list(source).use { children -> children.forEach { child -> if (child.fileName.toString() != "hradar.lock") copy(child, target.resolve(child.fileName.toString())) } }
        } else {
            directory(target.parent)
            Files.copy(source, target); fileMode(target)
            check(Files.mismatch(source, target) == -1L) { "Migration copy verification failed" }
            FileChannel.open(target, StandardOpenOption.WRITE).use { it.force(true) }
        }
    }
    private fun fingerprint(path: Path): String {
        val hash = MessageDigest.getInstance("SHA-256")
        Files.walk(path).use { paths -> paths.sorted().forEach { entry ->
            if (entry.fileName.toString() != "hradar.lock") {
                hash.update(path.relativize(entry).toString().toByteArray())
                if (Files.isRegularFile(entry)) Files.newInputStream(entry).use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
            }
        } }
        return java.util.HexFormat.of().formatHex(hash.digest())
    }
    private fun atomic(path: Path, content: String) {
        safe(path); directory(path.parent)
        val temporary = Files.createTempFile(path.parent, ".profile-", ".tmp", PosixFilePermissions.asFileAttribute(FILE_MODE))
        try {
            Files.writeString(temporary, content)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun removeStage(path: Path) { if (exists(path)) { safeTree(path); Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } } }
    private fun fileMode(path: Path) { Files.setPosixFilePermissions(path, FILE_MODE) }
    private fun exists(path: Path) = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    companion object {
        private val DIR_MODE = PosixFilePermissions.fromString("rwx------")
        private val FILE_MODE = PosixFilePermissions.fromString("rw-------")
    }
}
