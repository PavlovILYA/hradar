package dev.radar.research

import dev.radar.history.HistorySyncRecord
import dev.radar.telegram.TelegramMessage
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class RecruitmentReliabilityTest {
    private val source = MonitoredChannel(-1001, "synthetic_jobs", "Synthetic")
    private fun service(repo: Repositories) = RecruitmentProjectService(repo, MatchingService(), SubscriptionService(repo, MatchingService()), RecruiterService(repo, RecruiterSearchPort { SearchPage() }))
    private fun seed(repo: Repositories): RecruitmentProject {
        repo.saveChannel(source)
        repo.saveHistoryPage(HistorySyncRecord(source.id, source.username, source.title), listOf(TelegramMessage(1, 1700000000, "Java Spring Boot PostgreSQL synthetic", source.id)))
        return service(repo).save(ProjectInput("Synthetic", required = listOf("Java"), sources = listOf(source.id)))
    }
    @Test fun failedAssociationWriteRollsBackProjectAndSubscription() {
        val path = Files.createTempDirectory("recruitment-rollback").resolve("research.sqlite")
        Repositories(path).use { repo ->
            val p = seed(repo); val s = service(repo); val oldSubscription = s.subscribe(p.id); val old = s.get(p.id)
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { it.execute("CREATE TRIGGER reject_project_link BEFORE INSERT ON recruitment_templates BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") } }
            assertFails { s.save(old.criteria.copy(name = "Changed", required = listOf("Kotlin")), p.id) }
            assertEquals(old, s.get(p.id)); assertEquals(oldSubscription, repo.template(oldSubscription.id))
        }
    }
    @Test fun linkedMonitoringResultsArePagedAndSourceScoped() {
        Repositories(Files.createTempDirectory("recruitment-matches").resolve("research.sqlite")).use { repo ->
            val p = seed(repo); val s = service(repo); val template = s.subscribe(p.id)
            (2L..25L).forEach { id -> repo.recordMatch(template, ResearchMessage(id, source.id, 1700000000, "Synthetic Java", source.title, source.username)) }
            val other = repo.saveTemplate(SearchTemplate(name = "Unrelated", telegramQuery = "java", required = listOf("Java")))
            repo.recordMatch(other, ResearchMessage(99, source.id, 1700000000, "Unrelated Java", source.title, source.username))
            val first = s.matches(p.id, limit = 10); assertEquals(10, first.messages.size); assertNotNull(first.nextCursor)
            val next = s.matches(p.id, limit = 10, before = first.nextCursor!!); assertTrue(first.messages.map { it.id }.toSet().intersect(next.messages.map { it.id }.toSet()).isEmpty())
            assertFalse(first.messages.any { it.id == 99L }); repo.deleteChannel(source.id); assertTrue(s.matches(p.id).messages.isEmpty())
        }
    }
    @Test fun tenThousandCardPageRemainsBoundedAndHasStableTies() {
        val path = Files.createTempDirectory("recruitment-scale").resolve("research.sqlite")
        Repositories(path).use { repo ->
            val p = seed(repo); val s = service(repo); s.add(p.id, AddPublication(source.id, 1))
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { st ->
                st.execute("""WITH RECURSIVE numbers(n) AS (SELECT 2 UNION ALL SELECT n+1 FROM numbers WHERE n<10000)
                    INSERT INTO recruitment_cards SELECT 'synthetic-'||n,project_id,channel_id,n,stage,interest,normalized_text,normalized_notes,json_set(data,'$.id','synthetic-'||n,'$.message.id',n),created_at,updated_at FROM recruitment_cards,numbers WHERE message_id=1""")
            } }
            val first = s.cards(p.id, limit = 20); val next = s.cards(p.id, limit = 20, offset = 20)
            assertEquals(10000, first.total); assertEquals(20, first.items.size); assertEquals(20, next.items.size)
            assertTrue(first.items.map { it.id }.toSet().intersect(next.items.map { it.id }.toSet()).isEmpty())
            assertEquals(10000, s.statistics(p.id).total)
            assertEquals(0, s.cards(p.id, technology = "missing").total)
        }
    }
    @Test fun onlineEvidenceIsRealSourceBoundAndCriteriaChangesAreRejected() = runBlocking<Unit> {
        Repositories(Files.createTempDirectory("recruitment-network").resolve("research.sqlite")).use { repo ->
            val p = seed(repo)
            val message = ResearchMessage(2, source.id, 1700000000, "Ищу работу Java Spring Boot PostgreSQL", source.title, source.username, "https://t.me/synthetic_jobs/2")
            var change = false
            val matching = MatchingService()
            val recruiter = RecruiterService(repo, RecruiterSearchPort { request ->
                assertEquals("tracked", request.mode); assertEquals(listOf(source.username), request.usernames)
                if (change) service(repo).save(p.criteria.copy(required = listOf("Kotlin")), p.id)
                SearchPage(listOf(message))
            })
            val s = RecruitmentProjectService(repo, matching, SubscriptionService(repo, matching), recruiter)
            val found = s.search(p.id, ProjectSearch("telegram", "java")).messages.single()
            assertEquals(2, found.id)
            assertEquals(message.copy(explanation = found.explanation), s.add(p.id, AddPublication(source.id, 2)).message)
            change = true
            assertFailsWith<IllegalArgumentException> { s.search(p.id, ProjectSearch("telegram", "java")) }
        }
    }
}
