package dev.radar.research

import dev.radar.telegram.TdlibGateway
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

class MonitoringRecoveryTest {
    private val channel = MonitoredChannel(-1001, "synthetic_channel", "Synthetic")
    private fun message(id: Long, chat: Long = channel.id, text: String = "Java developer") = TdApi.Message().apply {
        this.id = id; chatId = chat; date = (System.currentTimeMillis() / 1000).toInt() + 10
        content = TdApi.MessageText().apply { this.text = TdApi.FormattedText(text, emptyArray()) }
    }
    private fun page(vararg messages: TdApi.Message) = TdApi.Messages().apply { this.messages = arrayOf(*messages); totalCount = messages.size }
    private fun template(repo: Repositories) = repo.saveTemplate(SearchTemplate(name = "Synthetic rule", required = listOf("Java"), excluded = listOf("Junior"), telegramQuery = "Java", sourceMode = "all", subscribed = true))
    private fun service(repo: Repositories, gateway: TdlibGateway, read: suspend (Long, Long, Int) -> TdApi.Messages, ready: Boolean = true): MonitoringService {
        val matching = MatchingService()
        return MonitoringService(gateway, repo, SearchService(gateway, repo, matching), NotificationService(repo, matching), read, { ready }, 0)
    }
    @Test fun actualUpdateNewMessageCallbackEnqueuesAndSubscriptionPauseResumeWorks() = runBlocking {
        val directory = Files.createTempDirectory("hradar-update-test")
        Repositories(directory.resolve("db.sqlite")).use { repo ->
            repo.saveChannel(channel); repo.saveState(MonitoringState(channel.id, 1)); val rule = template(repo)
            val gateway = TdlibGateway(1, "unused-test-value", directory)
            val monitor = service(repo, gateway, { _, _, _ -> error("No Telegram IO expected") }, ready = false)
            monitor.start()
            val callback = gateway.javaClass.getDeclaredMethod("onUpdate", TdApi.Object::class.java).apply { isAccessible = true }
            fun publish(id: Long) = callback.invoke(gateway, TdApi.UpdateNewMessage(message(id)))
            publish(2)
            withTimeout(5000) { while (repo.unreadCount() != 1) delay(5) }
            publish(2); delay(50); assertEquals(1, repo.notifications().size)
            repo.saveTemplate(rule.copy(active = false)); publish(3); delay(50); assertEquals(1, repo.notifications().size)
            repo.saveTemplate(rule.copy(active = true)); publish(4)
            withTimeout(5000) { while (repo.unreadCount() != 2) delay(5) }
            assertEquals(1L, repo.state(channel.id)!!.lastMessageId)
            monitor.close(); assertNull(gateway.messageListener)
        }
    }
    @Test fun boundedRecoveryPersistsSubscriptionAndCursorAcrossRestartWithoutDuplicates() = runBlocking {
        val directory = Files.createTempDirectory("hradar-recovery-test"); val path = directory.resolve("db.sqlite")
        Repositories(path).use { repo ->
            repo.saveChannel(channel); repo.saveState(MonitoringState(channel.id, 1)); template(repo)
            val gateway = TdlibGateway(1, "unused-test-value", directory)
            val monitor = service(repo, gateway, { _, cursor, _ -> page(message(if (cursor == 0L) 90 else cursor - 10)) })
            monitor.recover(channel)
            assertEquals(60L, repo.state(channel.id)!!.recoveryCursor)
            assertEquals(90L, repo.state(channel.id)!!.recoveryHead)
            assertEquals(4, repo.notifications().size)
            monitor.close()
        }
        Repositories(path).use { repo ->
            assertTrue(repo.templates().single().subscribed)
            val gateway = TdlibGateway(1, "unused-test-value", directory)
            val cursors = mutableListOf<Long>()
            val monitor = service(repo, gateway, { _, cursor, _ -> cursors.add(cursor); page(message(60), message(50, text = "Junior Java"), message(40), message(1)) })
            monitor.recover(channel)
            assertEquals(listOf(60L), cursors)
            assertEquals(5, repo.notifications().size) // replayed 60 deduped; Junior excluded
            assertEquals(90L, repo.state(channel.id)!!.lastMessageId)
            assertEquals(0L, repo.state(channel.id)!!.recoveryCursor)
            monitor.recover(channel)
            assertEquals(5, repo.notifications().size)
            monitor.close()
        }
    }
    @Test fun unavailableSourceDoesNotStopOtherSubscriptions() = runBlocking {
        val directory = Files.createTempDirectory("hradar-isolation-test")
        Repositories(directory.resolve("db.sqlite")).use { repo ->
            val other = channel.copy(id = -2001, username = "another_channel")
            repo.saveChannel(channel); repo.saveChannel(other)
            repo.saveState(MonitoringState(channel.id, 1)); repo.saveState(MonitoringState(other.id, 1)); template(repo)
            val gateway = TdlibGateway(1, "unused-test-value", directory)
            val monitor = service(repo, gateway, { id, _, _ -> if (id == channel.id) throw TelegramRequestException(403, "CHANNEL_PRIVATE") else page(message(2, id), message(1, id)) })
            monitor.pollOnce()
            assertNotNull(repo.state(channel.id)!!.error)
            assertEquals(1, repo.notifications().size)
            assertEquals(other.id, repo.notifications().single().message.channelId)
            monitor.close()
        }
    }
    @Test fun floodWaitCheckpointPreventsRequestsAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("hradar-monitor-limit-test"); val path = directory.resolve("db.sqlite")
        Repositories(path).use { repo ->
            repo.saveChannel(channel); repo.saveState(MonitoringState(channel.id, 1)); template(repo)
            val gateway = TdlibGateway(1, "unused-test-value", directory)
            val monitor = service(repo, gateway, { _, _, _ -> throw TelegramRequestException(429, "Too Many Requests: retry after 60") })
            monitor.pollOnce()
            assertTrue(repo.state(channel.id)!!.retryAt >= System.currentTimeMillis() + 59000)
            assertEquals(1L, repo.state(channel.id)!!.lastMessageId)
            monitor.close()
        }
        Repositories(path).use { repo ->
            val gateway = TdlibGateway(1, "unused-test-value", directory)
            val monitor = service(repo, gateway, { _, _, _ -> error("Persisted rate limit must prevent requests") })
            monitor.pollOnce(); monitor.close()
            assertEquals(0, repo.notifications().size)
        }
    }
}
