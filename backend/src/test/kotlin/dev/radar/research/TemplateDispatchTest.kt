package dev.radar.research

import dev.radar.telegram.TdlibGateway
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

class TemplateDispatchTest {
    @Test fun savedLegacyScopeOverridesStaleSelectedSourceModeAndKeys() = runBlocking {
        val dir = Files.createTempDirectory("template-dispatch")
        Repositories(dir.resolve("db")).use { repo ->
            repo.saveChannel(MonitoredChannel(1, "synthetic_alpha", "Synthetic alpha"))
            repo.saveChannel(MonitoredChannel(2, "synthetic_beta", "Synthetic beta"))
            val requests = mutableListOf<TdApi.SearchChatMessages>()
            val service = SearchService(TdlibGateway(1, "unused", dir), repo, MatchingService()) { fn ->
                when (fn) {
                    is TdApi.SearchChatMessages -> { requests += fn; TdApi.FoundChatMessages().apply { messages = emptyArray() } }
                    else -> error("Unexpected RPC ${fn.javaClass.simpleName}")
                }
            }
            for ((mode, expected) in listOf("all" to listOf(1L, 2L), "selected" to listOf(2L))) {
                val template = repo.saveTemplate(SearchTemplate(name = "Synthetic", required = listOf("Java"), telegramQuery = "Java", sourceMode = mode, sources = listOf(2)))
                for (keys in listOf(emptyList(), listOf("stale-source"))) {
                    requests.clear()
                    service.search(SearchRequest(mode = "sources", query = "stale", sourceKeys = keys, templateId = template.id))
                    assertEquals(expected, requests.map { it.chatId }); assertTrue(requests.all { it.query == "Java" })
                }
            }
        }
    }
    @Test fun globalTemplateIgnoresStaleLocalSourceKeysAndStillChecksFreeLimit() = runBlocking {
        val dir = Files.createTempDirectory("global-template-dispatch")
        Repositories(dir.resolve("db")).use { repo ->
            val template = repo.saveTemplate(SearchTemplate(name = "Synthetic", required = listOf("Java"), telegramQuery = "Java", sourceMode = "global", sourceKeys = listOf("previous-local-key")))
            val calls = mutableListOf<String>()
            val service = SearchService(TdlibGateway(1, "unused", dir), repo, MatchingService()) { fn ->
                calls += fn.javaClass.simpleName
                when (fn) {
                    is TdApi.GetPublicPostSearchLimits -> TdApi.PublicPostSearchLimits(3, 0, 60, 100L, false)
                    else -> error("Unexpected RPC")
                }
            }
            assertEquals("free_limit", service.search(SearchRequest(mode = "sources", sourceKeys = listOf("stale-source"), templateId = template.id)).state)
            assertEquals(listOf("GetPublicPostSearchLimits"), calls)
        }
    }

}
