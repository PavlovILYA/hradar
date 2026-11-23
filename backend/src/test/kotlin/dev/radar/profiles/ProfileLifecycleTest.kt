package dev.radar.profiles

import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.*

class ProfileLifecycleTest {
    @Test fun failureClosesEveryResourceButKeepsLeaseUntilProcessTermination() {
        val events=mutableListOf<String>()
        class Fatal:RuntimeException()
        assertFailsWith<Fatal> { closeProfileResources(listOf(
            {events+="monitor";throw IOException("synthetic")},{events+="bot"},{events+="tdlib-close";throw IOException("not closed")},{events+="sqlite"}
        ),{events+="release"},{events+="fatal";throw Fatal()}) }
        assertEquals(listOf("monitor","bot","tdlib-close","sqlite","fatal"),events)
    }
    @Test fun normalCloseReleasesOnlyAfterAllWorkersAndTdlibAcknowledge() {
        val events=mutableListOf<String>()
        closeProfileResources(listOf({events+="bot"},{events+="tdlib-closed"},{events+="db"}),{events+="release"},{throw AssertionError()})
        assertEquals(listOf("bot","tdlib-closed","db","release"),events)
    }
    @Test fun acceptedChangeCommitsWhenResponseWriteFailsOrIsCancelled() = runBlocking {
        var restarted=0
        assertFailsWith<IOException> { commitProfileChange({throw IOException("closed peer")},{restarted++}) }
        assertEquals(1,restarted)
        assertFailsWith<kotlinx.coroutines.CancellationException> { commitProfileChange({throw kotlinx.coroutines.CancellationException()},{restarted++}) }
        assertEquals(2,restarted)
    }
}
