package dev.radar.research

import dev.radar.telegram.TdlibGateway
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

class MonitoringTest {
    @Test fun newMessagePathRespectsSourcesSubscriptionsAndDeduplicates() = runBlocking {
        val directory = Files.createTempDirectory("hradar-monitor-test")
        Repositories(directory.resolve("research.sqlite")).use { repo ->
            val channel = repo.saveChannel(MonitoredChannel(-1001L, "synthetic_channel", "Synthetic"))
            repo.saveState(MonitoringState(channel.id, 1L))
            val template = repo.saveTemplate(SearchTemplate(name = "Synthetic Java", required = listOf("Java"), excluded = listOf("Junior"), telegramQuery = "Java", sourceMode = "selected", sources = listOf(channel.id), subscribed = true))
            val matching = MatchingService()
            // Never starts JNI, connects to Telegram, or needs account credentials.
            val gateway = TdlibGateway(1, "unused-test-value", directory)
            val notifications = NotificationService(repo, matching)
            val monitoring = MonitoringService(gateway, repo, SearchService(gateway, repo, matching), notifications)
            fun message(id: Long, text: String, chat: Long = channel.id) = TdApi.Message().apply {
                this.id = id; chatId = chat; date = (System.currentTimeMillis() / 1000).toInt()
                content = TdApi.MessageText().apply { this.text = TdApi.FormattedText(text, emptyArray()) }
            }
            monitoring.processUpdate(message(2L, "Java developer"))
            monitoring.processUpdate(message(2L, "Java developer"))
            monitoring.processUpdate(message(3L, "Junior Java developer"))
            monitoring.processUpdate(message(4L, "Java developer", -2001L))
            monitoring.processUpdate(message(1L, "Java developer"))
            assertEquals(1, repo.unreadCount())
            assertEquals(1, repo.notifications().size)
            assertTrue(repo.notifications().single().message.explanation!!.matches)
            assertEquals(mapOf(channel.id to 1), notifications.summary().byChannel)
            repo.saveTemplate(template.copy(subscribed = false))
            monitoring.processUpdate(message(5L, "Java developer"))
            assertEquals(1, repo.notifications().size)
            // Live events must not overwrite durable recovery checkpoints.
            assertEquals(1L, repo.state(channel.id)!!.lastMessageId)
            monitoring.close()
        }
    }
}
