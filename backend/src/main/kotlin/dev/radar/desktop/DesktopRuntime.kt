package dev.radar.desktop

import dev.radar.telegram.ApiCredentials
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Base64

class RuntimePaths(val home: Path = Path.of(System.getProperty("user.home"))) {
    val data = home.resolve(".hradar")
    val session = data.resolve("tdlib")
    val database = data.resolve("data/research.sqlite")
    val credentials = data.resolve("config/telegram-api.json")
    val backups = data.resolve("backups")
    val logs = data.resolve("logs")
    fun prepare() {
        privateDirectory(data)
        StorageMigration(this).run()
        listOf(database.parent, credentials.parent, session).forEach(::privateDirectory)
    }
    fun loadCredentials(): ApiCredentials? {
        val id = System.getenv("TG_API_ID")?.toIntOrNull()
        val hash = System.getenv("TG_API_HASH")
        if (id != null && hash != null) return ApiCredentials(id, hash).also { it.validate() }
        if (!Files.exists(credentials)) return null
        Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-------"))
        return try { Json.decodeFromString<ApiCredentials>(Files.readString(credentials)).also { it.validate() } }
        catch (_: Exception) { throw IllegalStateException("Конфигурация Telegram повреждена") }
    }
    fun saveCredentials(value: ApiCredentials) {
        value.validate()
        privateDirectory(credentials.parent)
        val temp = Files.createTempFile(credentials.parent, "api-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.writeString(temp, Json.encodeToString(ApiCredentials.serializer(), value))
            Files.move(temp, credentials, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
    private fun privateDirectory(path: Path) {
        Files.createDirectories(path)
        check(!Files.isSymbolicLink(path)) { "Каталог hradar не может быть симлинком" }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    }
}
class SessionLease private constructor(private val channel: FileChannel, private val lock: FileLock): AutoCloseable {
    override fun close() { lock.release(); channel.close() }
    companion object {
        fun acquire(directory: Path): SessionLease? {
            val lockPath = directory.resolve("hradar.lock")
            check(!Files.isSymbolicLink(lockPath)) { "Файл блокировки не может быть симлинком" }
            val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            Files.setPosixFilePermissions(directory.resolve("hradar.lock"), PosixFilePermissions.fromString("rw-------"))
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            if (lock == null) { channel.close(); return null }
            return SessionLease(channel, lock)
        }
    }
}
object DesktopRuntime {
    val enabled = System.getProperty("hradar.desktop") == "true"
    val smoke = System.getProperty("hradar.smoke") == "true"
    val port = if (smoke) System.getProperty("hradar.smokePort", "8080").toInt().also { require(it in 1024..65535) } else 8080
    fun token(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun portOccupied(): Boolean = try { Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 300) }; true } catch (_: java.io.IOException) { false }
    fun open(url: String) {
        // Use LaunchServices via java.desktop: don't expose the private launch token in child argv.
        if (enabled && !smoke) java.awt.Desktop.getDesktop().browse(URI(url))
    }
    fun conflict(message: String) {
        System.err.println(message)
        if (enabled && !smoke) {
            javax.swing.JOptionPane.showMessageDialog(null, message, "hradar", javax.swing.JOptionPane.WARNING_MESSAGE)
            // No client has been created on conflict paths; don't retain an AWT-only process.
            kotlin.system.exitProcess(0)
        }
    }
    fun reopen(paths: RuntimePaths): Boolean {
        val marker = paths.data.resolve("desktop-instance.json")
        return try {
            val obj = Json.parseToJsonElement(Files.readString(marker)) as kotlinx.serialization.json.JsonObject
            val pid = (obj["pid"] as kotlinx.serialization.json.JsonPrimitive).content.toLong()
            if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && portOccupied()) {
                val token = (obj["token"] as kotlinx.serialization.json.JsonPrimitive).content
                open("http://127.0.0.1:$port/#launch=$token"); true
            } else false
        } catch (_: Exception) { false }
    }
}
