package dev.radar.research

import dev.radar.telegram.TdlibGateway
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

/** Exercises the actual SearchService through fake RPCs, never starts the gateway. */
class SearchAdapterAuditTest {
    private val alpha = MonitoredChannel(-1001, "synthetic_alpha", "Synthetic alpha")
    private val beta = MonitoredChannel(-1002, "synthetic_beta", "Synthetic beta")
    private fun message(id: Long, source: Long) = TdApi.Message().apply {
        this.id = id; chatId = source; date = 1700000000
        content = TdApi.MessageText().apply { text = TdApi.FormattedText("Synthetic Java Spring Boot", emptyArray()) }
    }
    private fun page(source: Long, next: Long = 0) = TdApi.FoundChatMessages().apply {
        totalCount = 3; messages = (3L downTo 1L).map { message(it, source) }.toTypedArray(); nextFromMessageId = next
    }
    @Test fun linkRateLimitStopsAllFurtherRpcsAndKeepsFetchedPublicationsAndCursor() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("search-link-rate-limit")
        Repositories(dir.resolve("db.sqlite")).use { repo ->
            repo.saveChannel(alpha); repo.saveChannel(beta)
            val calls = mutableListOf<String>(); var limited = true
            val s = SearchService(TdlibGateway(1, "unused", dir), repo, MatchingService()) { query ->
                calls.add(query.javaClass.simpleName)
                when(query) {
                    is TdApi.SearchChatMessages -> if (query.chatId == alpha.id && query.fromMessageId == 0L) page(alpha.id, 99) else if (query.chatId == alpha.id) TdApi.FoundChatMessages().apply { messages = emptyArray() } else page(beta.id)
                    is TdApi.GetMessageLink -> if (limited) throw TelegramRequestException(429, "FLOOD_WAIT_12") else TdApi.MessageLink().apply { link = "https://t.me/synthetic_beta/${query.messageId}" }
                    else -> error("Unexpected RPC")
                }
            }
            val input = SearchRequest("tracked", "java", listOf(alpha.username, beta.username), limit = 10)
            val first = s.search(input)
            assertEquals(listOf("SearchChatMessages", "GetMessageLink"), calls)
            assertEquals("rate_limited", first.state); assertEquals(listOf(3L, 2L, 1L), first.messages.map { it.id }); assertTrue(first.cursor.isNotEmpty())
            assertTrue(first.messages.all { it.link == null })
            limited = false; calls.clear()
            val next = s.search(input.copy(cursor = first.cursor))
            assertEquals(beta.id, next.messages.first().channelId); assertEquals(3, next.messages.size); assertTrue(next.cursor.isEmpty())
        }
    }
    @Test fun projectSearchReturnsFetchedEvidenceEvenWhenOptionalLinkLookupWouldTimeOut() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("project-slow-link")
        Repositories(dir.resolve("db.sqlite")).use { repo ->
            repo.saveChannel(alpha); val matcher = MatchingService(); var linkCalls = 0
            val raw = SearchService(TdlibGateway(1, "unused", dir), repo, matcher) { query ->
                when(query) {
                    is TdApi.SearchChatMessages -> TdApi.FoundChatMessages().apply { messages = arrayOf(message(1, alpha.id)) }
                    is TdApi.GetMessageLink -> { linkCalls++; kotlinx.coroutines.delay(10_000); TdApi.MessageLink() }
                    else -> error("Unexpected RPC")
                }
            }
            val service = RecruitmentProjectService(repo, matcher, SubscriptionService(repo, matcher), RecruiterService(repo, RecruiterSearchPort(raw::search)), searchTimeoutMs = 500)
            val p = service.save(ProjectInput("Synthetic", required = listOf("Java"), sources = listOf(alpha.id)))
            val result = service.search(p.id, ProjectSearch("telegram", "java"))
            assertEquals(1, result.messages.size); assertTrue(result.exhausted); assertEquals(0, linkCalls)
        }
    }
    @Test fun secondSourceRateLimitKeepsFirstSourceAndDoesNotRefetchItOnResume() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("search-source-rate-limit")
        Repositories(dir.resolve("db.sqlite")).use { repo ->
            repo.saveChannel(alpha); repo.saveChannel(beta)
            val searched = mutableListOf<Long>(); var limited = true
            val s = SearchService(TdlibGateway(1, "unused", dir), repo, MatchingService()) { query ->
                when(query) {
                    is TdApi.SearchChatMessages -> {
                        searched.add(query.chatId)
                        if (query.chatId == beta.id && limited) throw TelegramRequestException(429, "FLOOD_WAIT_20")
                        page(query.chatId)
                    }
                    is TdApi.GetMessageLink -> TdApi.MessageLink().apply { link = "https://t.me/synthetic_alpha/${query.messageId}" }
                    else -> error("Unexpected RPC")
                }
            }
            val input = SearchRequest("tracked", "java", listOf(alpha.username, beta.username), limit = 10)
            val first = s.search(input)
            assertEquals("rate_limited", first.state); assertEquals(3, first.messages.size)
            limited = false
            val next = s.search(input.copy(cursor = first.cursor))
            assertEquals(listOf(alpha.id, beta.id, beta.id), searched)
            assertEquals(3, next.messages.size); assertTrue(next.messages.all { it.channelId == beta.id }); assertTrue(next.cursor.isEmpty())
        }
    }
}
