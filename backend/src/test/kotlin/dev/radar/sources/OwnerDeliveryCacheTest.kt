package dev.radar.sources

import dev.radar.research.*
import java.nio.file.Files
import kotlin.test.*

class OwnerDeliveryCacheTest {
    @Test fun deliveryClaimsSurviveRestartAndHonorSettingsWithoutBackfill() {
        val path = Files.createTempDirectory("delivery-cache").resolve("db")
        var id = ""
        Repositories(path).use { repo ->
            repo.saveChannel(MonitoredChannel(1, "synthetic", "Synthetic QA"))
            val rule = repo.saveTemplate(SearchTemplate(name = "Java", required = listOf("java"), telegramQuery = "java", subscribed = true))
            val service = NotificationService(repo, MatchingService())
            fun send(number: Long) = service.process(repo.channels().single(), ResearchMessage(number, 1, Int.MAX_VALUE, "synthetic java", "QA", "synthetic"))
            send(1)
            assertTrue(repo.claimDesktopDeliveries().isEmpty())
            repo.saveDesktopPreferences(DesktopPreferences(enabled = true))
            assertTrue(repo.claimDesktopDeliveries().isEmpty())
            send(2); send(2)
            val delivery = repo.claimDesktopDeliveries().single()
            id = delivery.id
            assertEquals("Новое совпадение", delivery.title)
            assertFalse(delivery.body.contains("java", ignoreCase = true))
            assertTrue(repo.claimDesktopDeliveries().isEmpty())
            repo.saveTemplate(rule.copy(notificationsEnabled = false)); send(3)
            assertTrue(repo.claimDesktopDeliveries().isEmpty())
        }
        Repositories(path).use { repo ->
            assertTrue(repo.claimDesktopDeliveries().isEmpty())
            assertNotNull(repo.inboxItem(id))
            repo.cacheSearch("synthetic", SearchPage(listOf(ResearchMessage(4, 1, 1, "synthetic", "QA", null))), 1000)
            assertNotNull(repo.cachedSearch("synthetic", 2000))
            assertNull(repo.cachedSearch("synthetic", 1000 + 86_400_001))
            repo.clearSearchCache()
            assertNull(repo.cachedSearch("synthetic", 1001))
            assertEquals(3, repo.notifications().size)
            assertEquals(3, repo.inboxPage(25, Long.MAX_VALUE).items.size)
        }
    }
}
