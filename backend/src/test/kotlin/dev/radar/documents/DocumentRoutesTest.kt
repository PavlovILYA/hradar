package dev.radar.documents

import dev.radar.desktop.*
import dev.radar.research.*
import dev.radar.sources.*
import io.ktor.http.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

class DocumentRoutesTest {
    @Test fun ownerDiscoversExplicitlyDownloadsAndSearchesContentsThroughProtectedRoutes() = testApplication {
        val dir = Files.createTempDirectory("documents-http-").toRealPath()
        val repo = Repositories(dir.resolve("research.sqlite"))
        val source = repo.saveSource(TelegramSource(chatId=1,type=SourceType.PRIVATE,title="Synthetic QA"))
        val td = Files.createDirectories(dir.resolve("tdlib/documents"))
        val body = "Synthetic resume: Java Spring Boot PostgreSQL Kafka"
        val original = Files.writeString(td.resolve("native-file"),body)
        var downloads = 0
        val nativeFile = TdApi.File().apply {
            id=7; size=Files.size(original)
            remote=TdApi.RemoteFile().apply { id="synthetic-remote"; uniqueId="synthetic-unique" }
            local=TdApi.LocalFile().apply { path=original.toString(); isDownloadingCompleted=true; downloadedSize=size }
        }
        val message = TdApi.Message().apply {
            id=42;chatId=1;date=1700000000;canBeSaved=true
            content=TdApi.MessageDocument().apply { caption=TdApi.FormattedText("Attached resume",emptyArray()); document=TdApi.Document().apply { fileName="resume.txt";mimeType="text/plain";document=nativeFile } }
        }
        val rpc: suspend (TdApi.Function<*>) -> TdApi.Object = { fn -> when(fn) {
            is TdApi.GetChat -> TdApi.Chat().apply { id=1; title="Synthetic QA";type=TdApi.ChatTypePrivate(1) }
            is TdApi.GetUser -> TdApi.User().apply { type=TdApi.UserTypeRegular() }
            is TdApi.GetChatHistory -> TdApi.Messages().apply { messages=if(fn.fromMessageId==0L) arrayOf(message) else emptyArray() }
            is TdApi.GetMessage -> message
            is TdApi.DownloadFile -> { downloads++;nativeFile }
            is TdApi.CancelDownloadFile -> TdApi.Ok()
            else -> error("Unexpected synthetic RPC")
        } }
        val service = DocumentService(repo,SourceReadService(repo,execute=rpc),dir.resolve("documents"),td,rpc,pollMillis=1)
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults=true }) }
            configureDesktopSecurity(DesktopAccess("synthetic-cookie"),"synthetic-csrf")
            configureDocuments(service)
        }
        fun HttpRequestBuilder.owner() {
            header(HttpHeaders.Host,"127.0.0.1:8080");header(HttpHeaders.Cookie,"hradar-desktop=synthetic-cookie")
            header(HttpHeaders.Origin,"http://127.0.0.1:8080");header("X-Hradar-CSRF","synthetic-csrf")
        }
        try {
            assertEquals(HttpStatusCode.Forbidden,client.get("/api/documents") { header(HttpHeaders.Host,"127.0.0.1:8080") }.status)
            assertEquals(HttpStatusCode.Forbidden,client.post("/api/documents/discover") {
                owner();headers.remove("X-Hradar-CSRF");contentType(ContentType.Application.Json);setBody("{}")
            }.status)
            val discovered=client.post("/api/documents/discover") {
                owner();contentType(ContentType.Application.Json);setBody(Json.encodeToString(DocumentDiscoverInput(source.key)))
            }
            assertEquals(HttpStatusCode.OK,discovered.status)
            val record=Json.decodeFromString<DocumentDiscovery>(discovered.bodyAsText()).items.single()
            assertEquals(0,downloads)
            assertEquals(HttpStatusCode.OK,client.post("/api/documents/${record.id}/download") { owner() }.status)
            withTimeout(15000) { while(repo.document(record.id)!!.record.state!="READY") delay(25) }
            val found=client.get("/api/documents/search?q=Spring%20Boot") { owner() }
            val hit=Json.decodeFromString<DocumentSearch>(found.bodyAsText()).items.single()
            assertEquals(42L,hit.document.message.id);assertEquals(source.key,hit.document.message.sourceKey)
            assertTrue(hit.snippet.contains("Spring Boot"));assertFalse(hit.document.message.text!!.contains("Spring Boot"))
            val file=client.get("/api/documents/${record.id}/file") { owner() }
            assertEquals(body,file.bodyAsText());assertEquals("nosniff",file.headers["X-Content-Type-Options"])
            assertTrue(file.headers[HttpHeaders.ContentDisposition]!!.startsWith("attachment"))
            for (suffix in listOf("file","text")) assertEquals(HttpStatusCode.Forbidden,client.get("/api/documents/${record.id}/$suffix") { owner();headers.remove(HttpHeaders.Origin);header(HttpHeaders.Origin,"https://evil.invalid") }.status)
            assertEquals(1,downloads);assertTrue(repo.mcpSources().isEmpty())
        } finally { service.close();repo.close();dir.toFile().deleteRecursively() }
    }
}
