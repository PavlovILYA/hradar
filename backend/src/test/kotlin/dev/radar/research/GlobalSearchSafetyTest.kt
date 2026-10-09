package dev.radar.research

import dev.radar.telegram.TdlibGateway
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

class GlobalSearchSafetyTest {
    @Test fun checksLimitsAndNeverRequestsPaidSearch() = runBlocking {
        val directory = Files.createTempDirectory("hradar-global-test")
        Repositories(directory.resolve("db.sqlite")).use { repo ->
            val gateway = TdlibGateway(1, "unused", directory)
            val calls = mutableListOf<String>()
            val service = SearchService(gateway, repo, MatchingService()) { query ->
                calls.add(query.javaClass.simpleName)
                when (query) {
                    is TdApi.GetPublicPostSearchLimits -> TdApi.PublicPostSearchLimits(3, 1, 0, 100L, false)
                    is TdApi.SearchPublicPosts -> {
                        assertEquals(0L, query.starCount)
                        TdApi.FoundPublicPosts(emptyArray(), "", TdApi.PublicPostSearchLimits(), false)
                    }
                    else -> error("Unexpected request")
                }
            }
            assertEquals("ok", service.search(SearchRequest(mode = "global", query = "synthetic query")).state)
            assertEquals(listOf("GetPublicPostSearchLimits", "SearchPublicPosts"), calls)
            calls.clear()
            val blocked = SearchService(gateway, repo, MatchingService()) { query ->
                calls.add(query.javaClass.simpleName)
                TdApi.PublicPostSearchLimits(3, 0, 3600, 100L, false)
            }
            assertEquals("free_limit", blocked.search(SearchRequest(mode = "global", query = "synthetic query")).state)
            assertEquals(listOf("GetPublicPostSearchLimits"), calls)
        }
    }
    @Test fun reportsUnsupportedInsteadOfSubstitutingTrackedSearch() = runBlocking {
        val directory = Files.createTempDirectory("hradar-unsupported-test")
        Repositories(directory.resolve("db.sqlite")).use { repo ->
            val gateway = TdlibGateway(1, "unused", directory)
            val service = SearchService(gateway, repo, MatchingService()) { throw TelegramRequestException(400, "METHOD_NOT_SUPPORTED") }
            val page = service.search(SearchRequest(mode = "global", query = "synthetic query"))
            assertEquals("unsupported", page.state)
            assertTrue(page.messages.isEmpty())
        }
    }
}
