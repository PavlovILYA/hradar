package dev.radar.profiles

import kotlinx.coroutines.*
import org.drinkless.tdlib.TdApi
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.*

class AvatarServiceTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("synthetic-avatar").toRealPath()
        val session = Files.createDirectories(root.resolve("session"))
        val cache = root.resolve("cache")
        var path: Path = session.resolve("image.png")
        var complete = true
        var declaredSize = 100L
        var secret = false
        var fileId = 7
        val calls = CopyOnWriteArrayList<TdApi.Function<*>>()
        init { ImageIO.write(BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", path.toFile()) }
        fun file() = TdApi.File().apply {
            id = fileId; size = declaredSize
            remote = TdApi.RemoteFile().apply { uniqueId = "synthetic-$fileId" }
            local = TdApi.LocalFile().apply { path = this@Fixture.path.toString(); isDownloadingCompleted = complete }
        }
        suspend fun execute(fn: TdApi.Function<*>): TdApi.Object {
            calls += fn
            return when (fn) {
                is TdApi.GetMe -> TdApi.User().apply { profilePhoto = TdApi.ProfilePhoto().apply { small = file() } }
                is TdApi.GetChat -> TdApi.Chat().apply {
                    id = fn.chatId; type = if (secret) TdApi.ChatTypeSecret() else TdApi.ChatTypePrivate(1)
                    photo = TdApi.ChatPhotoInfo().apply { small = file() }
                }
                is TdApi.DownloadFile, is TdApi.GetFile -> file()
                is TdApi.CancelDownloadFile -> TdApi.Ok()
                else -> error("Unexpected synthetic RPC")
            }
        }
        fun service(timeout: Long = 1000) = AvatarService(cache, session, ::execute, timeoutMillis = timeout, pollMillis = 5)
        override fun close() { root.toFile().deleteRecursively() }
    }

    @Test fun `normalizes image and caches only in own profile`() = runBlocking {
        Fixture().use { a -> Fixture().use { b ->
            val service = a.service()
            val bytes = assertNotNull(service.self())
            assertEquals(10, ImageIO.read(bytes.inputStream()).width)
            a.complete = false
            assertContentEquals(bytes, service.self())
            assertTrue(a.calls.none { it is TdApi.DownloadFile })
            assertFalse(Files.exists(b.cache))
            assertTrue(Files.list(a.cache).use { it.allMatch { p -> p.fileName.toString().endsWith(".png") } })
        } }
    }

    @Test fun `rejects oversize metadata secret chats and paths outside session`() = runBlocking {
        Fixture().use { f ->
            f.declaredSize = AvatarService.MAX_BYTES.toLong() + 1
            assertNull(f.service().self())
            assertTrue(f.calls.none { it is TdApi.DownloadFile })
            f.declaredSize = 100
            f.secret = true
            assertFailsWith<IllegalArgumentException> { f.service().chat(1) }
            f.path = Files.createTempFile(f.root, "outside", ".png")
            assertNull(f.service().self())
        }
    }

    @Test fun `rejects symlink and image pixel bombs before decoding`() = runBlocking {
        Fixture().use { f ->
            val image = f.path
            f.path = f.session.resolve("link.png")
            Files.createSymbolicLink(f.path, image)
            assertNull(f.service().self())
            f.path = image
            ImageIO.write(BufferedImage(1001, 1000, BufferedImage.TYPE_BYTE_BINARY), "png", image.toFile())
            assertNull(f.service().self())
        }
    }

    @Test fun `incomplete download times out and cancels existing gateway request`() = runBlocking {
        Fixture().use { f ->
            f.complete = false
            assertNull(f.service(50).self())
            assertTrue(f.calls.any { it is TdApi.DownloadFile && !it.synchronous && it.limit == (AvatarService.MAX_BYTES + 1).toLong() })
            assertTrue(f.calls.any { it is TdApi.CancelDownloadFile && it.fileId == f.fileId })
        }
    }

    @Test fun `download identity cannot change`() = runBlocking {
        Fixture().use { f ->
            f.complete = false
            val service = AvatarService(f.cache, f.session, { request ->
                if (request is TdApi.DownloadFile) f.file().apply { id = 99 } else f.execute(request)
            })
            assertFailsWith<IllegalArgumentException> { service.self() }
            assertTrue(f.calls.any { it is TdApi.CancelDownloadFile })
        }
    }
    @Test fun `queue admits two active and eight waiting requests`() = runBlocking {
        Fixture().use { f ->
            val release = CompletableDeferred<Unit>()
            val started = java.util.concurrent.atomic.AtomicInteger()
            val service = AvatarService(f.cache, f.session, { request ->
                if (request is TdApi.GetMe) { started.incrementAndGet(); release.await() }
                f.execute(request)
            })
            val jobs = (1..10).map { async(start = CoroutineStart.UNDISPATCHED) { service.self() } }
            withTimeout(1000) { while (started.get() < 2) delay(1) }
            assertNull(service.self())
            assertEquals(2, started.get())
            release.complete(Unit)
            jobs.awaitAll().forEach { assertNotNull(it) }
        }
    }

    @Test fun `cache eviction and actual native size remain bounded`() = runBlocking {
        Fixture().use { f ->
            Files.createDirectories(f.cache)
            repeat(40) { index -> Files.write(f.cache.resolve(index.toString().padStart(64, '0') + ".png"), ByteArray(AvatarService.MAX_BYTES)) }
            assertNotNull(f.service().self())
            assertTrue(Files.list(f.cache).use { stream -> stream.mapToLong { Files.size(it) }.sum() } <= AvatarService.CACHE_BYTES)
            f.fileId++
            Files.write(f.path, ByteArray(AvatarService.MAX_BYTES + 1))
            assertNull(f.service().self())
        }
    }

}
