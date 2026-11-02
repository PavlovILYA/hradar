package dev.radar.desktop

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.*
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.*

class DesktopShutdownTest {
    @Test fun `shutdown waits until the response has actually flushed`() = checkShutdown(false)
    @Test fun `shutdown completes after a Connection close response`() = checkShutdown(true)

    private fun checkShutdown(closeConnection: Boolean): Unit = runBlocking {
        val flushHeld = CompletableFuture<ChannelHandlerContext>()
        val releaseFlush = CompletableFuture<Unit>()
        val shutdown = CompletableFuture<Unit>()
        val server = embeddedServer(Netty, configure = {
            connector { host = "127.0.0.1"; port = 0 }
            channelPipelineConfig = {
                addFirst("hold-response-flush", object : ChannelOutboundHandlerAdapter() {
                    private var responseQueued = false
                    override fun write(ctx: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
                        responseQueued = true
                        ctx.write(message, promise)
                    }
                    override fun flush(ctx: ChannelHandlerContext) {
                        if (!responseQueued || releaseFlush.isDone) ctx.flush() else flushHeld.complete(ctx)
                    }
                })
            }
        }) {
            routing { install(ContentNegotiation) { json() } }
            routing { post("/api/desktop/shutdown") { call.respondDesktopShutdown { shutdown.complete(Unit) } } }
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().single().port
        val connection = URI("http://127.0.0.1:$port/api/desktop/shutdown").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        if (closeConnection) connection.setRequestProperty("Connection", "close")
        try {
            val response = CompletableFuture.supplyAsync {
                connection.responseCode to connection.inputStream.bufferedReader().use { it.readText() }
            }
            val ctx = flushHeld.get(5, SECONDS)
            assertFalse(response.isDone, "Fixture must hold the actual socket response")
            assertFalse(shutdown.isDone, "Shutdown must not run while the response is still buffered")
            ctx.executor().submit { releaseFlush.complete(Unit); ctx.flush() }.sync()
            val received = response.get(5, SECONDS)
            assertEquals(200 to "{\"ok\":true}", received)
            shutdown.get(5, SECONDS)
        } finally {
            releaseFlush.complete(Unit)
            flushHeld.getNow(null)?.let { ctx -> ctx.executor().submit { ctx.flush() }.sync() }
            connection.disconnect()
            server.stop(500, 3000)
        }
    }
}
