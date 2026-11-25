package dev.radar.profiles

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.TdApi
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/** Owner-local, lazy thumbnails. Native paths and Telegram objects never leave this service. */
class AvatarService(
    private val profileCache: Path,
    private val sessionRoot: Path,
    private val execute: suspend (TdApi.Function<*>) -> TdApi.Object,
    private val timeoutMillis: Long = 5_000,
    private val pollMillis: Long = 100,
) {
    private val slots = Semaphore(2)
    private val pending = AtomicInteger()
    private val cacheLock = Any()

    suspend fun self(): ByteArray? = bounded {
        val user = execute(TdApi.GetMe()) as TdApi.User
        image(user.profilePhoto?.small)
    }

    suspend fun chat(chatId: Long): ByteArray? = bounded {
        require(chatId != 0L)
        val chat = execute(TdApi.GetChat(chatId)) as TdApi.Chat
        require(chat.id == chatId && chat.type !is TdApi.ChatTypeSecret)
        image(chat.photo?.small)
    }

    private suspend fun bounded(block: suspend () -> ByteArray?): ByteArray? {
        if (pending.incrementAndGet() > 10) { pending.decrementAndGet(); return null }
        return try {
            withTimeout(timeoutMillis) { slots.withPermit { withContext(Dispatchers.IO) { block() } } }
        } catch (_: TimeoutCancellationException) { null }
        finally { pending.decrementAndGet() }
    }

    private suspend fun image(initial: TdApi.File?): ByteArray? {
        if (initial == null || initial.id <= 0 || initial.size > MAX_BYTES || initial.expectedSize > MAX_BYTES) return null
        val key = MessageDigest.getInstance("SHA-256").digest("${initial.id}:${initial.remote?.uniqueId.orEmpty()}".toByteArray())
            .joinToString("") { "%02x".format(it) } + ".png"
        synchronized(cacheLock) { readCache(key)?.let { return it } }
        var downloading = false
        try {
            var file: TdApi.File = initial
            if (file.local?.isDownloadingCompleted != true) {
                downloading = true
                file = execute(TdApi.DownloadFile(initial.id, 1, 0, (MAX_BYTES + 1).toLong(), false)) as TdApi.File
                while (file.local?.isDownloadingCompleted != true) {
                    checkFile(file, initial.id)
                    delay(pollMillis)
                    file = execute(TdApi.GetFile(initial.id)) as TdApi.File
                }
            }
            checkFile(file, initial.id)
            val path = Path.of(file.local?.path ?: return null)
            val bytes = safeRead(path, sessionRoot) ?: return null
            val normalized = normalize(bytes) ?: return null
            synchronized(cacheLock) { store(key, normalized) }
            downloading = false
            return normalized
        } finally {
            if (downloading) withContext(NonCancellable) {
                try { withTimeout(1_000) { execute(TdApi.CancelDownloadFile(initial.id, false)) } }
                catch (_: Exception) { /* Best effort after bounded request, never expose RPC details. */ }
            }
        }
    }

    private fun checkFile(file: TdApi.File, id: Int) {
        require(file.id == id && file.size <= MAX_BYTES && file.expectedSize <= MAX_BYTES &&
            (file.local?.downloadedSize ?: 0) <= MAX_BYTES) { "Avatar unavailable" }
    }

    private fun safeRead(path: Path, root: Path): ByteArray? {
        val parent = root.toRealPath()
        val absolute = path.toAbsolutePath().normalize()
        if (!absolute.startsWith(parent)) return null
        var node = parent
        for (part in parent.relativize(absolute)) {
            node = node.resolve(part)
            if (Files.isSymbolicLink(node)) return null
        }
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) || !absolute.toRealPath().startsWith(parent)) return null
        Files.newByteChannel(absolute, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            if (channel.size() !in 1..MAX_BYTES.toLong()) return null
            val buffer = ByteBuffer.allocate(MAX_BYTES + 1)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            if (buffer.position() > MAX_BYTES) return null
            return buffer.array().copyOf(buffer.position())
        }
    }

    private fun normalize(bytes: ByteArray): ByteArray? {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width.toLong() * height > 1_000_000) return null
                val image = reader.read(0)
                val out = ByteArrayOutputStream()
                if (!ImageIO.write(image, "png", out) || out.size() > MAX_BYTES) return null
                return out.toByteArray()
            } finally { reader.dispose() }
        }
    }

    private fun readCache(key: String): ByteArray? {
        if (!Files.exists(profileCache, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(profileCache)) return null
        return safeRead(profileCache.resolve(key), profileCache)
    }

    private fun store(key: String, bytes: ByteArray) {
        Files.createDirectories(profileCache)
        require(!Files.isSymbolicLink(profileCache)) { "Avatar cache unavailable" }
        val entries = Files.list(profileCache).use { stream ->
            stream.filter { it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.png")) && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted(compareBy { Files.getLastModifiedTime(it, LinkOption.NOFOLLOW_LINKS).toMillis() }).toList()
        }
        var size = entries.sumOf { Files.size(it) }
        for (entry in entries) {
            if (size + bytes.size <= CACHE_BYTES) break
            size -= Files.size(entry); Files.delete(entry)
        }
        val temporary = Files.createTempFile(profileCache, "avatar-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, profileCache.resolve(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        internal const val MAX_BYTES = 512 * 1024
        internal const val CACHE_BYTES = 20 * 1024 * 1024
    }
}
