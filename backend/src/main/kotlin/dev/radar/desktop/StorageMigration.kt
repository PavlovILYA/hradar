package dev.radar.desktop

import dev.radar.telegram.ApiCredentials
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID

/** Copies established user data into the unified layout before SQLite or TDLib starts. */
internal class StorageMigration(private val paths: RuntimePaths) {
    private val oldRoot = paths.home.resolve(".recruitment-radar")
    private val oldSession = oldRoot.resolve("tdlib")
    private val oldDatabase = paths.data.resolve("research.sqlite")
    private val oldCredentials = paths.data.resolve("telegram-api.json")
    private val oldBotConfiguration = paths.data.resolve("telegram-bot.json")
    private val marker = paths.data.resolve("storage-migration-v1.complete")

    fun run() {
        for (database in listOf(oldDatabase, paths.database)) {
            if (!Files.exists(database, LinkOption.NOFOLLOW_LINKS))
                check(listOf("-wal", "-shm").none { Files.exists(database.resolveSibling(database.fileName.toString() + it), LinkOption.NOFOLLOW_LINKS) }) {
                    "Найдены SQLite WAL/SHM без основной базы; запуск остановлен"
                }
        }
        val hasDatabase = Files.exists(oldDatabase, LinkOption.NOFOLLOW_LINKS)
        val hasCredentials = Files.exists(oldCredentials, LinkOption.NOFOLLOW_LINKS)
        val hasSession = Files.exists(oldSession, LinkOption.NOFOLLOW_LINKS)
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) { "Недопустимый маркер переноса" }
            val migrated = Files.readAllLines(marker).toSet()
            check(migrated.any { it in setOf("database", "credentials", "tdlib") } &&
                (!(hasDatabase || "database" in migrated) || Files.isRegularFile(paths.database, LinkOption.NOFOLLOW_LINKS)) &&
                (!(hasCredentials || "credentials" in migrated) || Files.isRegularFile(paths.credentials, LinkOption.NOFOLLOW_LINKS)) &&
                (!(hasSession || "tdlib" in migrated) || Files.isDirectory(paths.session, LinkOption.NOFOLLOW_LINKS))) {
                "Перенос был завершён, но новые файлы отсутствуют; восстановите их из резервной копии"
            }
            assertNoOpenFiles(listOfNotNull(if (hasSession) oldRoot else null, if (hasDatabase || hasCredentials) paths.data else null))
            check((!hasDatabase || "source-database=${databaseFingerprint(oldDatabase)}" in migrated) &&
                (!hasCredentials || "source-credentials=${fingerprint(oldCredentials)}" in migrated) &&
                (!hasSession || "source-tdlib=${fingerprint(oldSession)}" in migrated)) {
                "Старые данные изменились после переноса; не запускайте новую версию до ручного согласования копий"
            }
            migrateBotConfiguration()
            return
        }
        if (!hasDatabase && !hasCredentials && !hasSession) {
            migrateBotConfiguration()
            return
        }
        try {
            val lockPath = paths.data.resolve("storage-migration.lock")
            check(!Files.isSymbolicLink(lockPath)) { "Файл блокировки не может быть симлинком" }
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                Files.setPosixFilePermissions(lockPath, FILE_MODE)
                val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                check(lock != null) { "Перенос данных уже выполняется" }
                lock.use {
                    val legacyLease = if (hasSession) {
                        check(Files.isDirectory(oldSession, LinkOption.NOFOLLOW_LINKS)) { "Старый каталог TDLib недоступен" }
                        SessionLease.acquire(oldSession) ?: error("Старая Telegram-сессия занята")
                    } else null
                    try {
                        assertNoOpenFiles(listOfNotNull(if (hasSession) oldRoot else null, if (hasDatabase || hasCredentials) paths.data else null))
                        migrate(hasDatabase, hasCredentials, hasSession)
                        migrateBotConfiguration()
                    } finally { legacyLease?.close() }
                }
            }
        } catch (ex: Exception) {
            throw IllegalStateException("Перенос локальных данных остановлен; оригиналы сохранены. ${ex.message}", ex)
        }
    }

    private fun migrateBotConfiguration() {
        if (!Files.exists(oldBotConfiguration, LinkOption.NOFOLLOW_LINKS)) return
        check(Files.isRegularFile(oldBotConfiguration, LinkOption.NOFOLLOW_LINKS)) { "Старая конфигурация бота недоступна" }
        check(Files.isReadable(oldBotConfiguration)) { "Старая конфигурация бота недоступна" }
        assertNoOpenFiles(listOf(paths.data))
        compatible(oldBotConfiguration, paths.botConfiguration)
        if (Files.exists(paths.botConfiguration, LinkOption.NOFOLLOW_LINKS)) return
        privateDirectory(paths.backups)
        val backup = paths.backups.resolve("migration-${UUID.randomUUID()}")
        privateDirectory(backup)
        val backupFile = backup.resolve("config/telegram-bot.json")
        copyVerified(oldBotConfiguration, backupFile)
        publish(backupFile, paths.botConfiguration)
    }

    private fun migrate(db: Boolean, config: Boolean, tdlib: Boolean) {
        val originalDatabase = if (db) databaseFingerprint(oldDatabase) else null
        val originalCredentials = if (config) fingerprint(oldCredentials) else null
        val originalSession = if (tdlib) fingerprint(oldSession) else null
        fun originalsUnchanged() {
            check((!db || databaseFingerprint(oldDatabase) == originalDatabase) &&
                (!config || fingerprint(oldCredentials) == originalCredentials) &&
                (!tdlib || fingerprint(oldSession) == originalSession)) {
                "Старые файлы изменились во время переноса; запуск остановлен"
            }
        }
        val sources = buildList {
            if (db) {
                add(oldDatabase)
                listOf("-wal", "-shm").map { oldDatabase.resolveSibling(oldDatabase.fileName.toString() + it) }
                    .filterTo(this) { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            }
            if (config) add(oldCredentials)
            if (tdlib) add(oldSession)
        }
        val needed = sources.sumOf(::bytes) * 2L
        check(Files.getFileStore(paths.data).usableSpace > needed) { "Недостаточно места для резервной копии и переноса" }
        privateDirectory(paths.backups)
        val backup = paths.backups.resolve("migration-${UUID.randomUUID()}")
        privateDirectory(backup)
        val dbBackup = backup.resolve("data/research.sqlite")
        val configBackup = backup.resolve("config/telegram-api.json")
        val tdlibBackup = backup.resolve("tdlib")
        if (db) {
            copyVerified(oldDatabase, dbBackup)
            for (suffix in listOf("-wal", "-shm")) {
                val source = oldDatabase.resolveSibling("research.sqlite$suffix")
                if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) copyVerified(source, dbBackup.resolveSibling("research.sqlite$suffix"))
            }
            validateDatabase(dbBackup)
        }
        if (config) {
            copyVerified(oldCredentials, configBackup)
            try { Json.decodeFromString<ApiCredentials>(Files.readString(configBackup)).validate() }
            catch (_: Exception) { throw IllegalStateException("Старая конфигурация Telegram повреждена") }
        }
        if (tdlib) copyTree(oldSession, tdlibBackup)
        if (db) {
            compatible(dbBackup, paths.database)
            for (suffix in listOf("-wal", "-shm")) {
                val source = dbBackup.resolveSibling("research.sqlite$suffix")
                val target = paths.database.resolveSibling("research.sqlite$suffix")
                if (Files.exists(source)) compatible(source, target)
                else check(!Files.exists(target)) { "Непарный SQLite sidecar: $target" }
            }
        }
        if (config) compatible(configBackup, paths.credentials)
        if (tdlib) compatible(tdlibBackup, paths.session)
        originalsUnchanged()
        // Every source remains in place. A failure after any publication is safely retryable.
        if (db) {
            publish(dbBackup, paths.database)
            for (suffix in listOf("-wal", "-shm")) {
                val source = dbBackup.resolveSibling("research.sqlite$suffix")
                if (Files.exists(source)) publish(source, paths.database.resolveSibling("research.sqlite$suffix"))
            }
        }
        if (config) publish(configBackup, paths.credentials)
        if (tdlib) publish(tdlibBackup, paths.session)
        assertNoOpenFiles(listOfNotNull(if (tdlib) oldRoot else null, if (db || config) paths.data else null))
        originalsUnchanged()
        val manifest = buildList {
            if (db) add("database")
            if (config) add("credentials")
            if (tdlib) add("tdlib")
            if (db) add("source-database=$originalDatabase")
            if (config) add("source-credentials=$originalCredentials")
            if (tdlib) add("source-tdlib=$originalSession")
            add("backup=${backup.fileName}")
        }.joinToString("\n", postfix = "\n")
        Files.writeString(marker, manifest,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        Files.setPosixFilePermissions(marker, FILE_MODE)
        FileChannel.open(marker, StandardOpenOption.WRITE).use { it.force(true) }
        if (Files.isDirectory(oldRoot) && Files.list(oldRoot).use { stream -> stream.anyMatch { it.fileName.toString() != "tdlib" } })
            System.err.println("В старом каталоге остались дополнительные файлы; проверьте их вручную.")
        val known = setOf("research.sqlite", "research.sqlite-wal", "research.sqlite-shm", "telegram-api.json", "telegram-bot.json",
            "data", "config", "tdlib", "backups", "logs", "desktop-instance.json", "storage-migration.lock", "storage-migration-v1.complete")
        if (Files.list(paths.data).use { stream -> stream.anyMatch { it.fileName.toString() !in known } })
            System.err.println("В корне хранилища остались нераспознанные файлы; проверьте их вручную.")
    }

    private fun validateDatabase(path: Path) {
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toUri()}?mode=ro").use { db ->
                db.createStatement().use { statement ->
                    val version = statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
                    check(version in 1..12) { "Неизвестная версия старой SQLite" }
                    val required = buildSet {
                        addAll(listOf("monitored_channels", "search_templates"))
                        if (version >= 2) add("imported_messages")
                        if (version >= 8) addAll(listOf("recruitment_projects", "recruitment_cards"))
                        if (version >= 9) addAll(listOf("bot_access", "bot_invitations"))
                        if (version >= 10) add("telegram_sources")
                        if (version >= 12) addAll(listOf("local_subscriptions", "local_matches", "local_monitoring_limits"))
                        if (version >= 11) addAll(listOf("local_publications", "local_source_membership", "local_source_sync", "local_project_publications"))
                    }
                    val actual = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rows ->
                        buildSet { while (rows.next()) add(rows.getString(1)) }
                    }
                    check(actual.containsAll(required)) { "Старая SQLite не содержит ожидаемые таблицы" }
                    val integrity = statement.executeQuery("PRAGMA integrity_check").use { it.next(); it.getString(1) }
                    check(integrity == "ok") { "Старая SQLite повреждена" }
                    check(!statement.executeQuery("PRAGMA foreign_key_check").use { it.next() }) { "Нарушена целостность ссылок SQLite" }
                }
            }
        } catch (ex: Exception) { throw IllegalStateException("Проверка SQLite не пройдена", ex) }
    }

    private fun publish(source: Path, target: Path) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            check(equalTree(source, target)) { "Конфликт старого и нового пути: $target" }
            return
        }
        privateDirectory(target.parent)
        val staging = target.resolveSibling("${target.fileName}.migrating-${UUID.randomUUID()}")
        try {
            if (Files.isDirectory(source)) copyTree(source, staging) else copyVerified(source, staging)
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } finally { if (Files.exists(staging)) staging.toFile().deleteRecursively() }
    }

    private fun compatible(source: Path, target: Path) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            check(equalTree(source, target)) { "Конфликт старого и нового пути: $target" }
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(!Thread.currentThread().isInterrupted) { "Перенос отменён" }
                check(!attrs.isSymbolicLink) { "Симлинк в переносимых данных" }
                privateDirectory(target.resolve(source.relativize(dir)))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(!Thread.currentThread().isInterrupted) { "Перенос отменён" }
                check(attrs.isRegularFile && !attrs.isSymbolicLink) { "Необычный файл в Telegram-сессии" }
                copyVerified(file, target.resolve(source.relativize(file)))
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun copyVerified(source: Path, target: Path) {
        check(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "Необычный исходный файл: $source" }
        privateDirectory(target.parent)
        Files.copy(source, target)
        Files.setPosixFilePermissions(target, FILE_MODE)
        check(equalFile(source, target)) { "Копия отличается от исходного файла: $source" }
        FileChannel.open(target, StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun equalTree(a: Path, b: Path): Boolean {
        if (Files.isRegularFile(a, LinkOption.NOFOLLOW_LINKS)) return Files.isRegularFile(b, LinkOption.NOFOLLOW_LINKS) && equalFile(a, b)
        if (!Files.isDirectory(a, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(b, LinkOption.NOFOLLOW_LINKS)) return false
        val namesA = Files.list(a).use { it.map { file -> file.fileName.toString() }.toList().sorted() }
        val namesB = Files.list(b).use { it.map { file -> file.fileName.toString() }.toList().sorted() }
        return namesA == namesB && namesA.all { equalTree(a.resolve(it), b.resolve(it)) }
    }

    private fun equalFile(a: Path, b: Path): Boolean {
        if (Files.size(a) != Files.size(b)) return false
        return fileDigest(a).contentEquals(fileDigest(b))
    }

    private fun databaseFingerprint(database: Path): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (suffix in listOf("", "-wal", "-shm")) {
            val file = database.resolveSibling(database.fileName.toString() + suffix)
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                hash.update(suffix.toByteArray())
                hash.update(fingerprint(file).toByteArray())
            }
        }
        return java.util.HexFormat.of().formatHex(hash.digest())
    }

    private fun fingerprint(path: Path): String {
        val hash = MessageDigest.getInstance("SHA-256")
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return java.util.HexFormat.of().formatHex(fileDigest(path))
        check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "Необычный файл в исходных данных" }
        Files.walk(path).use { stream ->
            stream.sorted().forEach { child ->
                check(!Files.isSymbolicLink(child)) { "Симлинк в исходных данных" }
                hash.update(path.relativize(child).toString().toByteArray())
                if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) hash.update(fileDigest(child))
                else check(Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) { "Необычный файл в исходных данных" }
            }
        }
        return java.util.HexFormat.of().formatHex(hash.digest())
    }

    private fun fileDigest(path: Path): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                check(!Thread.currentThread().isInterrupted) { "Перенос отменён" }
                val count = stream.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest()
    }

    private fun bytes(path: Path): Long = if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        Files.walk(path).use { stream -> stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong(Files::size).sum() }
    else Files.size(path)

    private fun assertNoOpenFiles(directories: List<Path>) {
        for (directory in directories.distinct()) {
            val process = ProcessBuilder("lsof", "-Fpn", "+D", directory.toString()).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            val ownLocks = listOf(paths.data.resolve("storage-migration.lock"), oldSession.resolve("hradar.lock"))
                .filter(Files::exists).map { it.toRealPath().toString() }.toSet()
            var pid = -1L
            val busy = output.lineSequence().any { line ->
                if (line.startsWith("p")) pid = line.drop(1).toLongOrNull() ?: -1
                line.startsWith("n") && (pid != ProcessHandle.current().pid() ||
                    line.drop(1) !in ownLocks)
            }
            check(code in 0..1 && !busy && output.lineSequence().all { it.isBlank() || it.startsWith("p") || it.startsWith("f") || it.startsWith("n") }) {
                "Другой процесс использует старые файлы: $directory"
            }
        }
    }

    private fun privateDirectory(path: Path) {
        Files.createDirectories(path)
        check(!Files.isSymbolicLink(path)) { "Каталог не может быть симлинком: $path" }
        Files.setPosixFilePermissions(path, DIR_MODE)
    }

    companion object {
        private val DIR_MODE = PosixFilePermissions.fromString("rwx------")
        private val FILE_MODE = PosixFilePermissions.fromString("rw-------")
    }
}
