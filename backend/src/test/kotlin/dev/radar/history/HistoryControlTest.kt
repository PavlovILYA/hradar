package dev.radar.history

import dev.radar.research.MonitoredChannel
import dev.radar.research.Repositories
import dev.radar.telegram.TelegramMessage
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class HistoryControlTest {
    private val channel = MonitoredChannel(-1001, "synthetic_channel", "Synthetic")
    private fun message(id: Long) = TelegramMessage(id, 1700000000, "Synthetic", channel.id)
    private fun path() = Files.createTempDirectory("hradar-control-test").resolve("db.sqlite")
    private suspend fun waitFor(check: () -> Boolean) = withTimeout(5000) { while (!check()) delay(5) }

    @Test fun stopCancelsInFlightRequestIsIdempotentAndResumeDeduplicates() = runBlocking {
        val path = path()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        Repositories(path).use { repo ->
            val service = HistorySyncService(repo, { _, cursor ->
                if (cursor == 0L) listOf(message(30), message(20)) else {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
            }, { true }, 0, 0, 0)
            service.start(channel)
            withTimeout(5000) { entered.await() }
            val paused = service.stop(channel.id)!!
            assertTrue(cancelled.isCompleted)
            assertEquals(SyncStatus.PAUSED, paused.status)
            assertEquals(PauseReason.USER, paused.pauseReason)
            assertEquals(20L, paused.cursor)
            assertEquals(2, paused.messageCount)
            assertEquals(paused, service.stop(channel.id))
            service.close()
        }
        Repositories(path).use { repo ->
            val requests = AtomicInteger()
            val service = HistorySyncService(repo, { _, cursor -> requests.incrementAndGet(); if (cursor == 20L) listOf(message(20), message(10)) else emptyList() }, { true }, 0, 0, 0)
            service.resumePending(); delay(50)
            assertEquals(0, requests.get(), "User-paused import must not restart automatically")
            service.start(channel)
            waitFor { repo.historySync(channel.id)?.status == SyncStatus.COMPLETED }
            assertEquals(3, repo.historySync(channel.id)!!.messageCount)
            assertEquals(2, requests.get())
            service.close()
        }
    }
    @Test fun concurrentStartsRunOneJobAndStopResumeRemainSerialized() = runBlocking {
        Repositories(path()).use { repo ->
            val entered = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val service = HistorySyncService(repo, { _, _ -> calls.incrementAndGet(); entered.complete(Unit); awaitCancellation() }, { true }, 0, 0, 0)
            coroutineScope { repeat(20) { launch { service.start(channel) } } }
            withTimeout(5000) { entered.await() }
            assertEquals(1, calls.get())
            coroutineScope { repeat(10) { launch { service.stop(channel.id) } } }
            assertEquals(SyncStatus.PAUSED, repo.historySync(channel.id)!!.status)
            service.start(channel)
            waitFor { calls.get() == 2 }
            service.stop(channel.id)
            assertEquals(2, calls.get())
            service.close()
        }
    }
    @Test fun capIsExactAndIncreasingItResumesWithoutSkippingPageRemainder() = runBlocking {
        Repositories(path()).use { repo ->
            val state = repo.beginHistorySync(channel)
            repo.saveHistoryPage(state.copy(cursor = 1020), (1020L..2014L).map(::message)) // 995 saved
            val cursors = mutableListOf<Long>()
            val service = HistorySyncService(repo, { _, cursor ->
                cursors.add(cursor)
                when(cursor) { 1020L -> (1010L..1020L).map(::message); 1015L -> (1010L..1015L).map(::message); else -> emptyList() }
            }, { true }, 0, 0, 0)
            service.runImport(channel.id)
            val capped = repo.historySync(channel.id)!!
            assertEquals(SyncStatus.PAUSED, capped.status); assertEquals(PauseReason.LIMIT, capped.pauseReason)
            assertEquals(1000, capped.messageCount); assertEquals(1015L, capped.cursor)
            service.start(channel, 5000)
            waitFor { repo.historySync(channel.id)?.status == SyncStatus.COMPLETED }
            assertEquals(listOf(1020L, 1015L, 1010L), cursors)
            assertEquals(1005, repo.historySync(channel.id)!!.messageCount)
            service.close()
        }
    }
    @Test fun unlimitedRequiresNullAndSupportedLimitsPersist() {
        val path = path()
        Repositories(path).use { repo ->
            assertEquals(1000, repo.beginHistorySync(channel).maxMessages)
            assertFailsWith<IllegalArgumentException> { repo.beginHistorySync(channel, 2000) }
            repo.saveHistoryPage(repo.historySync(channel.id)!!.copy(status = SyncStatus.PAUSED), emptyList())
            assertNull(repo.beginHistorySync(channel, null).maxMessages)
        }
        Repositories(path).use { repo -> assertNull(repo.historySync(channel.id)!!.maxMessages) }
    }
    @Test fun floodWaitDelaysRequestsAndStopPreservesWaitAcrossResume() = runBlocking {
        Repositories(path()).use { repo ->
            val calls = AtomicInteger()
            val service = HistorySyncService(repo, { _, _ ->
                if (calls.incrementAndGet() == 1) throw TelegramRequestException(429, "FLOOD_WAIT_1")
                emptyList()
            }, { true }, 0, 0, 0)
            service.start(channel)
            waitFor { repo.historySync(channel.id)!!.nextRetryAt > 0 }
            val until = repo.historySync(channel.id)!!.nextRetryAt
            assertTrue(until > System.currentTimeMillis())
            val paused = service.stop(channel.id)!!
            assertEquals(until, paused.nextRetryAt)
            service.start(channel)
            delay(100)
            assertEquals(1, calls.get())
            waitFor { repo.historySync(channel.id)!!.status == SyncStatus.COMPLETED }
            assertTrue(System.currentTimeMillis() >= until)
            assertEquals(2, calls.get()); service.close()
        }
    }
    @Test fun sharedRateLimiterSpacesRequestsAcrossChannels() = runBlocking {
        Repositories(path()).use { repo ->
            val times = java.util.Collections.synchronizedList(mutableListOf<Long>())
            val service = HistorySyncService(repo, { _, _ -> times.add(System.nanoTime()); emptyList() }, { true }, 0, 0, 80)
            val other = channel.copy(id = -2001, username = "another_channel")
            service.start(channel); service.start(other)
            waitFor { repo.historySync(other.id)!!.status == SyncStatus.COMPLETED && repo.historySync(channel.id)!!.status == SyncStatus.COMPLETED }
            assertEquals(2, times.size)
            assertTrue((times[1] - times[0]) / 1_000_000 >= 70)
            service.close()
        }
    }
}
