package dev.radar.documents

import dev.radar.research.*
import dev.radar.sources.*
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
import org.drinkless.tdlib.TdApi
import java.nio.file.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class DocumentServiceTest {
    private class Fixture(timeoutMillis: Long = 200) : AutoCloseable {
        val root = Files.createTempDirectory("document-service").toRealPath()
        val telegram = Files.createDirectories(root.resolve("session/documents"))
        val sourceFile = telegram.resolve("synthetic.txt").also { Files.writeString(it,"Synthetic Kotlin resume") }
        val repo = Repositories(root.resolve("test.db"))
        val source = repo.saveSource(TelegramSource(chatId=1,type=SourceType.PRIVATE,title="Synthetic"))
        val calls = CopyOnWriteArrayList<TdApi.Function<*>>()
        var complete = true
        var wrongIdentity = false
        var protected = false
        var foreignChat = false
        var flood = false
        var networkFailure = false
        var path = sourceFile
        var size = Files.size(sourceFile)
        fun file() = TdApi.File().apply {
            id=42; this.size=this@Fixture.size
            remote=TdApi.RemoteFile().apply { uniqueId=if(wrongIdentity) "changed" else "stable" }
            local=TdApi.LocalFile().apply { this.path=this@Fixture.path.toString(); isDownloadingCompleted=complete; isDownloadingActive=!complete; canBeDownloaded=true; downloadedSize=if(complete) size else 0 }
        }
        fun message(id: Long) = TdApi.Message().apply {
            this.id=id; chatId=if(foreignChat) 2 else 1; canBeSaved=!protected
            content=TdApi.MessageDocument().apply { document=TdApi.Document().apply { fileName="synthetic.txt"; mimeType="text/plain"; document=file() } }
        }
        suspend fun execute(fn: TdApi.Function<*>): TdApi.Object {
            calls += fn
            return when(fn) {
                is TdApi.GetChat -> TdApi.Chat().apply { id=1; title="Synthetic"; type=TdApi.ChatTypePrivate(1); hasProtectedContent=protected }
                is TdApi.GetUser -> TdApi.User().apply { type=TdApi.UserTypeRegular() }
                is TdApi.GetMessage -> message(fn.messageId)
                is TdApi.GetChatHistory -> TdApi.Messages().apply { messages=if(fn.fromMessageId==0L) arrayOf(message(1),message(2)) else emptyArray() }
                is TdApi.DownloadFile -> if(networkFailure) throw TelegramRequestException(500,"Synthetic network failure") else if(flood) throw TelegramRequestException(429,"FLOOD_WAIT_60") else file()
                is TdApi.GetFile -> file()
                is TdApi.CancelDownloadFile -> TdApi.Ok()
                else -> error("Unexpected synthetic RPC")
            }
        }
        val reader=SourceReadService(repo,execute=::execute)
        val service=DocumentService(repo,reader,root.resolve("documents"),telegram,::execute,pollMillis=5,downloadTimeoutMillis=timeoutMillis)
        suspend fun await(id: String, state: String): DocumentRecord = withTimeout(10_000) {
            while(true) { val r=service.record(id); if(r.state==state) return@withTimeout r; delay(10) }; error("unreachable")
        }
        override fun close() { service.close(); repo.close(); root.toFile().deleteRecursively() }
    }
    @Test fun `discovery explicit download extraction search and cross publication dedup`() = runBlocking {
        Fixture().use { f ->
            val discovered=f.service.discover(DocumentDiscoverInput(f.source.key))
            assertEquals(2,discovered.items.size)
            assertTrue(f.calls.none { it is TdApi.DownloadFile })
            val first=discovered.items[0]; f.service.download(first.id); assertTrue(f.await(first.id,"READY").hasText)
            assertEquals("Synthetic Kotlin resume",f.service.text(first.id).text)
            f.service.download(discovered.items[1].id); f.await(discovered.items[1].id,"READY")
            assertEquals(2,f.service.search("Kotlin").total)
            assertEquals(f.size,f.service.list().bytes)
            assertEquals(1L,Files.list(f.root.resolve("documents")).use { it.count() })
            assertNotEquals(discovered.items[0].message.id,discovered.items[1].message.id)
        }
    }
    @Test fun `scope protected identity and filesystem boundaries fail closed`() = runBlocking {
        Fixture().use { f ->
            f.foreignChat=true; assertFailsWith<IllegalArgumentException> { f.service.register(f.source.key,1) }
            f.foreignChat=false; f.protected=true; assertFailsWith<IllegalArgumentException> { f.service.register(f.source.key,1) }
            f.protected=false; val record=f.service.register(f.source.key,1).single()
            f.wrongIdentity=true; f.service.download(record.id); f.await(record.id,"UNAVAILABLE")
            assertTrue(f.calls.none { it is TdApi.DownloadFile })
            f.wrongIdentity=false; f.path=f.root.resolve("session/database.sqlite").also { Files.writeString(it,"Synthetic Kotlin resume") }
            f.service.download(record.id); f.await(record.id,"ERROR")
            assertFalse(f.service.record(record.id).hasFile)
            val link=f.telegram.resolve("link.txt"); Files.createSymbolicLink(link,f.path)
            assertFalse(DocumentService.safeRegular(link,f.telegram))
        }
    }
    @Test fun `cancel timeout flood retry and restart retain explicit states`() = runBlocking {
        Fixture().use { f ->
            f.complete=false
            val record=f.service.register(f.source.key,1).single(); f.service.download(record.id)
            f.await(record.id,"DOWNLOADING"); withTimeout(1000) { while (f.calls.none { it is TdApi.DownloadFile }) delay(1) }; f.service.cancel(record.id)
            assertEquals("ERROR",f.service.record(record.id).state)
            assertTrue(f.calls.any { it is TdApi.CancelDownloadFile })
            f.service.download(record.id); f.await(record.id,"ERROR") // finite timeout
            f.complete=true; f.flood=true; f.service.download(record.id); val failed=f.await(record.id,"ERROR")
            assertTrue(failed.retryAt>System.currentTimeMillis())
            val other=f.service.register(f.source.key,2).single()
            assertFailsWith<IllegalArgumentException> { f.service.download(other.id) }
            val stored=f.repo.document(other.id)!!
            f.service.close()
            f.repo.saveDocument(stored.copy(record=stored.record.copy(state="PROCESSING")))
            DocumentService(f.repo,f.reader,f.root.resolve("documents"),f.telegram,f::execute).use { recovered ->
                assertEquals("ERROR",recovered.record(other.id).state)
                assertTrue(f.calls.none { it is TdApi.DownloadFile && it.fileId != 42 })
            }
        }
    }
    @Test fun `queue and size limits and empty documents are explicit`() = runBlocking {
        Fixture(10_000).use { f ->
            f.complete=false
            val first=f.service.register(f.source.key,1).single(); f.service.download(first.id)
            f.await(first.id,"DOWNLOADING")
            for (id in 2L..21L) f.service.download(f.service.register(f.source.key,id).single().id)
            val excess=f.service.register(f.source.key,22).single()
            assertFailsWith<IllegalArgumentException> { f.service.download(excess.id) }
            assertEquals("NOT_DOWNLOADED",f.service.record(excess.id).state)
            assertEquals(1,f.calls.count { it is TdApi.DownloadFile })
            f.size=DocumentService.MAX_FILE+1
            val large=f.service.register(f.source.key,23).single()
            assertEquals("UNAVAILABLE",large.state)
            assertFailsWith<IllegalArgumentException> { f.service.download(large.id) }
        }
        Fixture().use { f ->
            f.size=0; Files.write(f.sourceFile,byteArrayOf())
            val empty=f.service.register(f.source.key,1).single(); f.service.download(empty.id)
            assertTrue(f.await(empty.id,"EMPTY").hasFile)
            assertEquals(1,f.service.search("synthetic").total)
            assertFalse(f.service.record(empty.id).hasText)
        }
    }

    @Test fun `native cache quota prevents further download without deleting files`() = runBlocking {
        Fixture().use { f ->
            val cache=f.telegram.resolve("synthetic-cache.bin")
            java.io.RandomAccessFile(cache.toFile(),"rw").use { it.setLength(DocumentService.MAX_DISK-16) }
            val record=f.service.register(f.source.key,1).single()
            f.service.download(record.id); f.await(record.id,"ERROR")
            assertTrue(f.calls.none { it is TdApi.DownloadFile })
            assertEquals(DocumentService.MAX_DISK-16,Files.size(cache))
        }
    }

    @Test fun `network failure retries explicitly and completed file and index survive restart`() = runBlocking {
        Fixture().use { f ->
            val record=f.service.register(f.source.key,1).single()
            f.networkFailure=true;f.service.download(record.id);f.await(record.id,"ERROR")
            assertFalse(f.service.record(record.id).hasFile)
            f.networkFailure=false;f.service.download(record.id);f.await(record.id,"READY")
            f.service.download(record.id,true);f.await(record.id,"READY")
            assertEquals(1L,Files.list(f.root.resolve("documents")).use { it.count() })
            assertEquals(1,f.service.search("Kotlin").total)
            f.service.close()
            DocumentService(f.repo,f.reader,f.root.resolve("documents"),f.telegram,f::execute).use { reopened ->
                assertEquals("READY",reopened.record(record.id).state)
                assertEquals("Synthetic Kotlin resume",reopened.text(record.id).text)
                assertTrue(Files.isRegularFile(reopened.original(record.id).second))
                assertEquals(1,reopened.search("Kotlin").total)
            }
        }
    }

}
