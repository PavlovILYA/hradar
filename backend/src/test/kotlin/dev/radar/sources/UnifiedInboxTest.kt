package dev.radar.sources

import dev.radar.research.*
import java.nio.file.Files
import kotlin.test.*

class UnifiedInboxTest {
    @Test fun localMatchingAppearsInTheMainInboxWithoutPublicExposure() {
        Repositories(Files.createTempDirectory("unified-inbox").resolve("db")).use { repo ->
            val source = repo.saveSource(TelegramSource(chatId = 123, type = SourceType.PRIVATE, title = "Synthetic QA"))
            val template = repo.saveTemplate(SearchTemplate(name = "Java", required = listOf("java"), telegramQuery = "java"))
            val state = repo.configureLocalSubscription(source, template, null, true, 10, 1)
            val message = ResearchMessage(11, 123, 2000000000, "Synthetic java", source.title, null, sourceKey = source.key)
            repeat(2) { repo.commitLocalMonitoring(state, source, template, state, listOf(message), MatchingService()) }
            val inbox = NotificationService(repo, MatchingService())
            assertEquals(1, inbox.summary().unread)
            val item = inbox.page(25, Long.MAX_VALUE).items.single()
            assertEquals("Synthetic java", item.message.text)
            inbox.read(item.id)
            assertEquals(0, inbox.summary().unread)
            assertTrue(repo.localMatches().items.single().read)
            assertTrue(repo.notifications().isEmpty())
            assertTrue(repo.mcpSources().isEmpty())
        }
    }
}
