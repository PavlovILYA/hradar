package dev.radar.research

import dev.radar.history.HistorySyncRecord
import dev.radar.telegram.TelegramMessage
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

/** All publications are synthetic, all storage temporary, no TDLib client. */
class ProjectFilteredSearchTest {
    private val source = MonitoredChannel(-1001, "synthetic_jobs", "Synthetic source")
    private fun message(id: Long, text: String = "Java Spring Boot PostgreSQL") = ResearchMessage(id, source.id, 1700000000, text, source.title, source.username)
    private fun service(repo: Repositories, port: RecruiterSearchPort = RecruiterSearchPort { error("Unexpected Telegram call") }): RecruitmentProjectService {
        val matching = MatchingService()
        return RecruitmentProjectService(repo, matching, SubscriptionService(repo, matching), RecruiterService(repo, port))
    }
    private fun project(repo: Repositories, s: RecruitmentProjectService): RecruitmentProject {
        repo.saveChannel(source)
        return s.save(ProjectInput("Synthetic", required = listOf("Java", "Spring Boot", "PostgreSQL"), desired = listOf("Kafka"), excluded = listOf("junior"), sources = listOf(source.id)))
    }
    private fun database() = Repositories(Files.createTempDirectory("filtered-search-").resolve("research.sqlite"))

    @Test fun archiveSkipsSeveralUnmatchedPagesAndPreservesAndNotAndOptionalWords() = runBlocking<Unit> {
        database().use { repo ->
            val s = service(repo); val p = project(repo, s)
            val texts = (1L..32L).map { id -> TelegramMessage(id, 1700000000, when (id) {
                1L -> "Java Spring Boot PostgreSQL Kafka"
                2L -> "Java Spring Boot PostgreSQL" // Desired word is absent, still a match.
                3L -> "Java Spring Boot PostgreSQL junior" // Excluded.
                else -> "Java Spring Boot" // One required word is missing.
            }, source.id) }
            repo.saveHistoryPage(HistorySyncRecord(source.id, source.username, source.title), texts)
            val page = s.search(p.id, ProjectSearch(query = "java"))
            assertEquals(listOf(2L, 1L), page.messages.map { it.id }); assertEquals(32, page.scanned)
            assertEquals(4, page.pagesScanned); assertEquals(2, page.matched); assertTrue(page.exhausted); assertFalse(page.limitReached)
            assertEquals(listOf("Java", "Spring Boot", "PostgreSQL"), page.messages.first().explanation!!.foundRequired)
            assertTrue(page.messages.first().explanation!!.foundAny.isEmpty()); assertEquals(listOf("Kafka"), page.messages.last().explanation!!.foundAny)
            s.add(p.id, AddPublication(source.id, 2))
            assertEquals(listOf("-1001:2"), s.search(p.id, ProjectSearch(query = "java")).alreadyAdded)
            assertFailsWith<ResourceNotFound> { s.add(p.id, AddPublication(source.id, 99)) }
        }
    }
    @Test fun budgetReturnsContinuationThenFindsLaterArchiveMatchWithoutRepeats() = runBlocking<Unit> {
        database().use { repo ->
            val s = service(repo); val p = project(repo, s)
            repo.saveHistoryPage(HistorySyncRecord(source.id, source.username, source.title), (1L..75L).map {
                TelegramMessage(it, 1700000000, if (it <= 21) "Java Spring Boot PostgreSQL" else "Java", source.id)
            })
            var page = s.search(p.id, ProjectSearch(query = "java"))
            assertEquals(50, page.scanned); assertTrue(page.messages.isEmpty()); assertTrue(page.limitReached); assertFalse(page.exhausted)
            val emptyCursor = page.cursor; assertTrue(emptyCursor.isNotEmpty())
            val ids = mutableListOf<Long>()
            while (page.cursor.isNotEmpty()) {
                page = s.search(p.id, ProjectSearch(query = "java", cursor = page.cursor))
                assertTrue(page.messages.size <= 10 && page.pagesScanned <= 5 && page.scanned <= 50)
                ids.addAll(page.messages.map { it.id })
            }
            assertEquals((21L downTo 1L).toList(), ids); assertEquals(ids.size, ids.distinct().size); assertTrue(page.exhausted)
            assertFailsWith<IllegalArgumentException> { s.search(p.id, ProjectSearch(query = "spring", cursor = emptyCursor)) }
            assertFailsWith<IllegalArgumentException> { service(repo).search(p.id, ProjectSearch(query = "java", cursor = emptyCursor)) }
            val other = s.save(p.criteria.copy(name = "Other project"))
            assertFailsWith<IllegalArgumentException> { s.search(other.id, ProjectSearch(query = "java", cursor = emptyCursor)) }
            s.save(p.criteria.copy(required = listOf("Kotlin")), p.id)
            assertFailsWith<IllegalArgumentException> { s.search(p.id, ProjectSearch(query = "java", cursor = emptyCursor)) }
        }
    }
    @Test fun fakeTelegramOverlappingPagesAndSmallerLastFetchDoNotSkipOrRepeat() = runBlocking<Unit> {
        database().use { repo ->
            val limits = mutableListOf<Int>()
            val s = service(repo, RecruiterSearchPort { request ->
                limits.add(request.limit)
                when(request.cursor) {
                    "" -> SearchPage((1L..10L).map { message(it, if (it <= 8) "Java Spring Boot PostgreSQL" else "Java") }, "a")
                    "a" -> SearchPage(listOf(message(8), message(11)), "b") // Overlap, one new match.
                    "b" -> SearchPage(listOf(message(12)), "c")
                    "c" -> SearchPage(listOf(message(12), message(13)), "d")
                    "d" -> SearchPage(listOf(message(14)))
                    else -> error("Wrong underlying cursor")
                }
            }); val p = project(repo, s)
            val first = s.search(p.id, ProjectSearch("telegram", "java"))
            assertEquals(10, first.messages.size); assertEquals(listOf(10, 2, 1), limits); assertEquals(12, first.scanned)
            val next = s.search(p.id, ProjectSearch("telegram", "java", first.cursor))
            assertEquals(listOf(13L, 14L), next.messages.map { it.id }); assertEquals(2, next.scanned); assertTrue(next.exhausted)
            assertTrue(first.messages.map { it.id }.intersect(next.messages.map { it.id }.toSet()).isEmpty())
            assertEquals(14, s.add(p.id, AddPublication(source.id, 14)).message.id)
        }
    }
    @Test fun unchangedAndCyclingCursorsStopWithoutSpinning() = runBlocking<Unit> {
        database().use { repo ->
            var calls = 0
            val s = service(repo, RecruiterSearchPort { calls++; SearchPage(listOf(message(1, "Java")), "same") })
            val p = project(repo, s); val page = s.search(p.id, ProjectSearch("telegram", "java"))
            assertEquals(2, calls); assertEquals(1, page.scanned); assertEquals("cursor_stalled", page.state); assertTrue(page.cursor.isNotEmpty())
            val retry = s.search(p.id, ProjectSearch("telegram", "java", page.cursor))
            assertEquals(3, calls); assertEquals(0, retry.scanned)
        }
    }
    @Test fun sameMessageIdInDifferentSourcesIsNotADuplicate() = runBlocking<Unit> {
        database().use { repo ->
            val other = MonitoredChannel(-1002, "synthetic_other", "Synthetic other")
            val s = service(repo, RecruiterSearchPort { SearchPage(listOf(message(1), message(1).copy(channelId = other.id, title = other.title, username = other.username))) })
            val p = project(repo, s); repo.saveChannel(other)
            s.save(p.criteria.copy(sources = listOf(source.id, other.id)), p.id)
            val page = s.search(p.id, ProjectSearch("telegram", "java"))
            assertEquals(2, page.matched); assertEquals(2, page.scanned); assertTrue(page.exhausted)
            page.messages.forEach { s.add(p.id, AddPublication(it.channelId, it.id)) }
            assertEquals(2, s.statistics(p.id).total)
        }
    }
    @Test fun rateLimitPreservesSuccessfulMatchesAndRetriesOnlyFailedPage() = runBlocking<Unit> {
        database().use { repo ->
            val cursors = mutableListOf<String>(); var limited = true
            val s = service(repo, RecruiterSearchPort { request ->
                cursors.add(request.cursor)
                if (request.cursor.isEmpty()) SearchPage(listOf(message(1)), "next")
                else if (limited) throw TelegramRequestException(429, "FLOOD_WAIT_10")
                else SearchPage(listOf(message(1), message(2)))
            }); val p = project(repo, s)
            val first = s.search(p.id, ProjectSearch("telegram", "java"))
            assertEquals("rate_limited", first.state); assertEquals(1, first.matched); assertEquals(1, first.pagesScanned); assertFalse(first.exhausted)
            limited = false
            val next = s.search(p.id, ProjectSearch("telegram", "java", first.cursor))
            assertEquals(listOf(2L), next.messages.map { it.id }); assertEquals(listOf("", "next", "next"), cursors)
        }
    }
    @Test fun partialAndUnavailableTelegramAreExplicitAndBounded() = runBlocking<Unit> {
        database().use { repo ->
            var calls = 0
            val s = service(repo, RecruiterSearchPort { calls++; SearchPage(listOf(message(1)), "", "partial", "Synthetic unavailable source") })
            val p = project(repo, s); val page = s.search(p.id, ProjectSearch("telegram", "java"))
            assertEquals(1, calls); assertEquals("partial", page.state); assertFalse(page.exhausted); assertTrue(page.cursor.isNotEmpty())
            val failing = service(repo, RecruiterSearchPort { throw TelegramRequestException(500, "synthetic") })
            assertEquals("telegram_error", failing.search(p.id, ProjectSearch("telegram", "java")).state)
        }
    }
    @Test fun cancellationPropagatesAndDoesNotRememberUncommittedResults() = runBlocking<Unit> {
        database().use { repo ->
            val s = service(repo, RecruiterSearchPort { delay(10_000); SearchPage(listOf(message(88))) }); val p = project(repo, s)
            assertFailsWith<TimeoutCancellationException> { withTimeout(50) { s.search(p.id, ProjectSearch("telegram", "java")) } }
            assertFailsWith<ResourceNotFound> { s.add(p.id, AddPublication(source.id, 88)) }
        }
    }
    @Test fun ownDeadlineReturnsPartialEvidenceAndARecoverableContinuation() = runBlocking<Unit> {
        database().use { repo ->
            var slow = true
            val matching = MatchingService()
            val s = RecruitmentProjectService(repo, matching, SubscriptionService(repo, matching), RecruiterService(repo, RecruiterSearchPort { request ->
                if (request.cursor.isEmpty()) SearchPage(listOf(message(1)), "slow")
                else { if (slow) delay(10_000); SearchPage(listOf(message(2))) }
            }), searchTimeoutMs = 500)
            val p = project(repo, s); val first = s.search(p.id, ProjectSearch("telegram", "java"))
            assertEquals("search_timeout", first.state); assertTrue(first.limitReached); assertEquals(1, first.matched)
            assertEquals(1, s.add(p.id, AddPublication(source.id, 1)).message.id)
            slow = false
            val next = s.search(p.id, ProjectSearch("telegram", "java", first.cursor))
            assertEquals(listOf(2L), next.messages.map { it.id }); assertTrue(next.exhausted)
        }
    }
    @Test fun noSourcesEmptyArchiveNoQueryResultsAndMissingProjectAreDistinct() = runBlocking<Unit> {
        database().use { repo ->
            val s = service(repo); val p = project(repo, s)
            assertEquals("archive_empty", s.search(p.id, ProjectSearch(query = "java")).state)
            repo.saveHistoryPage(HistorySyncRecord(source.id, source.username, source.title), listOf(TelegramMessage(1, 1700000000, "Unrelated publication", source.id)))
            val page = s.search(p.id, ProjectSearch(query = "java")); assertEquals("ok", page.state); assertEquals(0, page.scanned); assertTrue(page.exhausted)
            repo.deleteChannel(source.id); assertEquals("no_sources", s.search(p.id, ProjectSearch(query = "java")).state)
            assertFailsWith<ResourceNotFound> { s.search("missing", ProjectSearch(query = "java")) }
        }
    }
}
