package dev.radar.sources

import dev.radar.research.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class UnifiedWorkflowTest {
    @Test fun savingTemplateCreatesSubscriptionsAtomicallyAndPreservesCheckpoints(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("unified-workflow").resolve("db")).use { repo ->
            val source = repo.saveSource(TelegramSource(chatId = 321, type = SourceType.BASIC_GROUP, title = "Synthetic QA"))
            val service = OwnerWorkflowService(repo, { key, _, _ -> SourceMessagePage(listOf(ResearchMessage(10, 321, 1, "old java", "QA", null, sourceKey = key))) })
            val template = service.save(SearchTemplate(name = "Java", required = listOf("java"), telegramQuery = "java", sourceMode = "chats", sourceKeys = listOf(source.key), subscribed = true))
            assertEquals(10L, repo.localSubscriptions().single().checkpoint)
            assertTrue(repo.localSubscriptions().single().enabled)
            service.save(template.copy(active = false))
            assertFalse(repo.localSubscriptions().single().enabled)
            service.save(template.copy(active = true))
            assertEquals(10L, repo.localSubscriptions().single().checkpoint)
            assertTrue(repo.localSubscriptions().single().enabled)
            val failing = OwnerWorkflowService(repo, { _, _, _ -> error("synthetic unavailable") })
            val second = repo.saveSource(TelegramSource(chatId = 322, type = SourceType.BASIC_GROUP, title = "Other QA"))
            assertFailsWith<IllegalStateException> { failing.save(template.copy(sourceKeys = listOf(second.key))) }
            assertEquals(listOf(source.key), repo.template(template.id)!!.sourceKeys)
            assertEquals(1, repo.localSubscriptions().size)
        }
    }
    @Test fun editingLegacyLocalOnlyTemplateDoesNotEnablePublicMonitoring(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("legacy-local-rule").resolve("db")).use { repo ->
            repo.saveChannel(MonitoredChannel(1, "synthetic", "Public QA"))
            val source = repo.saveSource(TelegramSource(chatId = 123, type = SourceType.PRIVATE, title = "Private QA"))
            val rule = repo.saveTemplate(SearchTemplate(name = "Java", required = listOf("java"), telegramQuery = "java", subscribed = false))
            repo.configureLocalSubscription(source, rule, null, true, 10, 1)
            val workflow = OwnerWorkflowService(repo, { _, _, _ -> error("Existing pairs must not reinitialize") })
            val updated = workflow.save(rule.copy(name = "Edited Java"))
            assertFalse(updated.subscribed)
            assertEquals("all", updated.sourceMode)
            assertTrue(repo.localSubscriptions().single().enabled)
            workflow.save(updated.copy(active = false))
            assertFalse(repo.template(rule.id)!!.active)
            assertFalse(repo.template(rule.id)!!.subscribed)
            assertEquals(10L, repo.localSubscriptions().single().checkpoint)
        }
    }

    @Test fun templateActivationSharesTheDurableMonitoringFloodGate(): Unit = runBlocking {
        val path = Files.createTempDirectory("owner-activation-rate").resolve("db")
        val instant = System.currentTimeMillis()
        Repositories(path).use { repo ->
            val reader = SourceReadService(repo) { error("No native RPC") }
            LocalMonitoringService(repo, reader, { true }, requestIntervalMs = 0,
                readRecent = { _, _, _ -> throw dev.radar.telegram.TelegramRequestException(429, "FLOOD_WAIT_600") }, now = { instant }).use { monitoring ->
                assertFailsWith<dev.radar.telegram.TelegramRequestException> { monitoring.activationPage("synthetic", 0, 1) }
            }
            assertEquals(instant + 600_000, repo.localMonitoringRetryAt())
        }
        Repositories(path).use { repo ->
            val reader = SourceReadService(repo) { error("No native RPC") }
            LocalMonitoringService(repo, reader, { true }, requestIntervalMs = 0,
                readRecent = { _, _, _ -> error("Must honor persisted rate deadline") }, now = { instant + 60_000 }).use { monitoring ->
                assertFailsWith<dev.radar.telegram.TelegramRequestException> { monitoring.activationPage("synthetic", 0, 1) }
            }
        }
    }

}
