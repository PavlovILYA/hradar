package dev.radar.sources

import dev.radar.research.*
import java.nio.file.Files
import kotlin.test.*

class OwnerInboxIdentityTest {
    @Test fun templateAndChatIdentityRemainDistinctWhileDuplicatesAndReadClaimsSurviveRestart() {
        val path = Files.createTempDirectory("inbox-identity").resolve("db")
        var readId = ""
        Repositories(path).use { repo ->
            repo.saveDesktopPreferences(DesktopPreferences(enabled = true))
            for (chat in 1L..2L) repo.saveChannel(MonitoredChannel(chat, "synthetic_$chat", "Synthetic"))
            val rules = (1..2).map { repo.saveTemplate(SearchTemplate(name = "Java", required = listOf("Java"), telegramQuery = "Java", subscribed = true)) }
            val notifications = NotificationService(repo, MatchingService())
            repeat(2) {
                for (channel in repo.channels()) notifications.process(channel, ResearchMessage(10, channel.id, Int.MAX_VALUE, "Synthetic Java", "Synthetic", channel.username))
            }
            val records = repo.inboxPage(25, Long.MAX_VALUE).items
            assertEquals(4, records.size)
            assertEquals(rules.flatMap { rule -> listOf(Triple(rule.id, 1L, 10L), Triple(rule.id, 2L, 10L)) }.toSet(), records.map { Triple(it.templateId, it.message.channelId, it.message.id) }.toSet())
            readId = records.first().id
            assertTrue(repo.readInbox(readId)); assertEquals(3, repo.inboxUnread())
            assertEquals(4, repo.claimDesktopDeliveries().size)
            assertTrue(repo.claimDesktopDeliveries().isEmpty())
        }
        Repositories(path).use { repo ->
            assertEquals(4, repo.inboxPage(25, Long.MAX_VALUE).items.size)
            assertEquals(3, repo.inboxUnread()); assertTrue(repo.inboxItem(readId)!!.read)
            assertTrue(repo.notifications().first { it.id == readId }.read)
            assertTrue(repo.claimDesktopDeliveries().isEmpty())
            val notifications = NotificationService(repo, MatchingService())
            for (channel in repo.channels()) notifications.process(channel, ResearchMessage(10, channel.id, Int.MAX_VALUE, "Synthetic Java", "Synthetic", channel.username))
            assertEquals(4, repo.inboxPage(25, Long.MAX_VALUE).items.size); assertEquals(3, repo.inboxUnread())
            assertTrue(repo.claimDesktopDeliveries().isEmpty())
        }
    }
}
