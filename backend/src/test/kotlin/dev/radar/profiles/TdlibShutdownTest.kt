package dev.radar.profiles

import dev.radar.telegram.awaitTdlibClosed
import dev.radar.telegram.logOutTdlib
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CountDownLatch
import kotlin.test.*

class TdlibShutdownTest {
    @Test fun `close confirmation is required and repeated confirmation is harmless`() {
        val closed = CountDownLatch(1)
        assertFailsWith<IllegalStateException> { awaitTdlibClosed(closed, 1) }
        closed.countDown()
        awaitTdlibClosed(closed, 1)
        awaitTdlibClosed(closed, 1)
    }

    @Test fun `explicit logout uses official operation and waits for closed`() = runBlocking {
        val events = mutableListOf<String>()
        logOutTdlib({ request ->
            assertIs<TdApi.LogOut>(request)
            events += "logout"; TdApi.Ok()
        }, { events += "closed" })
        assertEquals(listOf("logout", "closed"), events)
    }

    @Test fun `logout errors and missing close acknowledgment fail closed`() = runBlocking {
        var waited = false
        assertFailsWith<IllegalStateException> {
            logOutTdlib({ TdApi.Error(500, "synthetic") }, { waited = true })
        }
        assertFalse(waited)
        assertFailsWith<IllegalStateException> {
            logOutTdlib({ TdApi.Ok() }, { awaitTdlibClosed(CountDownLatch(1), 1) })
        }
    }
}
