package dev.radar.research

import dev.radar.history.HistorySyncRecord
import dev.radar.telegram.TelegramMessage
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class RecruitmentProjectTest {
    private val source = MonitoredChannel(-1001, "synthetic_jobs", "Synthetic source")
    private fun service(repo: Repositories) = RecruitmentProjectService(repo, MatchingService(), SubscriptionService(repo, MatchingService()), RecruiterService(repo, RecruiterSearchPort { SearchPage() }))
    private fun seed(repo: Repositories) {
        repo.saveChannel(source)
        repo.saveHistoryPage(HistorySyncRecord(source.id, source.username, source.title), listOf(TelegramMessage(1, 1700000000, "Ищу работу Senior Java Spring Boot PostgreSQL Kafka Docker", source.id)))
    }
    private fun input() = ProjectInput("Senior Java Backend", required = listOf("Java", "Spring Boot", "PostgreSQL"), desired = listOf("Kafka", "Docker"), excluded = listOf("junior"), sources = listOf(source.id))
    @Test fun lifecycleCriteriaTemplatesAndRestart() {
        val path = Files.createTempDirectory("recruitment-lifecycle").resolve("research.sqlite")
        var id = ""
        Repositories(path).use { repo ->
            seed(repo); val s = service(repo)
            val template = repo.saveTemplate(SearchTemplate(name = "Existing", telegramQuery = "java"))
            val p = s.save(input().copy(templateIds = listOf(template.id))); id = p.id
            assertEquals(listOf(template.id), s.get(id).criteria.templateIds)
            val edited = s.save(input().copy(name = "Java II", required = listOf("Kotlin")), id)
            assertEquals(p.createdAt, edited.createdAt); assertEquals(listOf("Kotlin"), s.get(id).criteria.required)
            val subscription = s.subscribe(id); assertTrue(subscription.subscribed); assertEquals(listOf(subscription.id), s.get(id).criteria.templateIds)
            assertTrue(subscription.any.isEmpty())
            assertEquals(subscription.id, s.subscribe(id).id)
            s.save(s.get(id).criteria.copy(required = listOf("Java")), id)
            assertEquals(listOf("Java"), repo.template(subscription.id)!!.required)
            s.save(s.get(id).criteria.copy(status = ProjectStatus.ARCHIVED), id)
            assertFailsWith<IllegalArgumentException> { s.add(id, AddPublication(source.id, 1)) }
        }
        Repositories(path).use { repo -> val s = service(repo); assertEquals(ProjectStatus.ARCHIVED, s.get(id).criteria.status); assertEquals(1, s.list(status = ProjectStatus.ARCHIVED).total); s.delete(id); assertFailsWith<ResourceNotFound> { s.get(id) } }
    }
    @Test fun publicationIsIndependentDeduplicatedAndPersisted() {
        val path = Files.createTempDirectory("recruitment-cards").resolve("research.sqlite")
        var id = ""; var cardId = ""
        Repositories(path).use { repo ->
            seed(repo); val s = service(repo); val first = s.save(input()); val second = s.save(input().copy(name = "Other project")); id = first.id
            val c = s.add(id, AddPublication(source.id, 1)); cardId = c.id
            assertEquals(c.id, s.add(id, AddPublication(source.id, 1)).id)
            val other = s.add(second.id, AddPublication(source.id, 1))
            s.update(id, c.id, CardUpdate(RecruitmentStage.CONTACTED, "Synthetic note", 5, PublicationKind.OTHER))
            assertEquals(RecruitmentStage.NEW, s.cards(second.id).items.single().stage)
            assertFailsWith<ResourceNotFound> { s.update(id, other.id, CardUpdate(RecruitmentStage.REJECTED)) }
            assertEquals(1, s.statistics(id).total)
            assertEquals(1, s.cards(id, query = "JAVA", technology = "postgresql", source = source.id, sort = "interest").total)
            assertEquals(0, s.cards(id, query = "not present").total)
            assertEquals(1, s.cards(id, query = "synthetic NOTE").total)
            assertEquals(0, s.cards(id, stage = RecruitmentStage.NEW).total)
        }
        Repositories(path).use { repo ->
            val s = service(repo); val c = s.cards(id).items.single(); assertEquals(cardId, c.id); assertEquals("Synthetic note", c.notes); assertEquals(5, c.interest); assertEquals(PublicationKind.OTHER, c.manualKind)
            assertEquals(PublicationKind.CANDIDATE, c.classification.kind); s.remove(id, cardId); assertEquals(0, s.cards(id).total)
        }
    }
    @Test fun deniedAndInvalidRecords() {
        Repositories(Files.createTempDirectory("recruitment-invalid").resolve("research.sqlite")).use { repo ->
            seed(repo); val s = service(repo); val p = s.save(input())
            assertFailsWith<IllegalArgumentException> { s.save(input().copy(name = " ")) }
            assertFailsWith<IllegalArgumentException> { s.save(input().copy(sources = listOf(-999))) }
            assertFailsWith<IllegalArgumentException> { s.save(input().copy(templateIds = listOf("missing"))) }
            assertFailsWith<ResourceNotFound> { s.save(input(), "missing") }
            assertFailsWith<ResourceNotFound> { s.add(p.id, AddPublication(source.id, 999)) }
            assertFailsWith<ResourceNotFound> { s.cards("missing") }
            assertFailsWith<ResourceNotFound> { s.remove(p.id, "missing") }
            assertFailsWith<IllegalArgumentException> { s.cards(p.id, limit = 10000) }
            assertFailsWith<IllegalArgumentException> { s.update(p.id, "missing", CardUpdate(RecruitmentStage.NEW, interest = 6)) }
            val c = s.add(p.id, AddPublication(source.id, 1)); repo.deleteChannel(source.id)
            assertFailsWith<ResourceNotFound> { s.add(p.id, AddPublication(source.id, 1)) }
            assertEquals(c.id, s.cards(p.id).items.single().id)
        }
    }
    @Test fun classifierExplainsUncertaintyAndIntent() {
        val c = PublicationClassifier()
        assertEquals(PublicationKind.CANDIDATE, c.classify("Ищу работу разработчиком Java и Spring Boot").kind)
        assertEquals(PublicationKind.EMPLOYER, c.classify("В нашу команду ищем разработчика Java PostgreSQL").kind)
        assertEquals(PublicationKind.OTHER, c.classify("Завтра вебинар по разработке приложений на Java").kind)
        assertEquals(PublicationKind.INSUFFICIENT, c.classify("Java").kind)
        assertEquals(PublicationKind.INSUFFICIENT, c.classify("Java Spring Boot PostgreSQL Kafka Docker").kind)
        assertEquals(PublicationKind.INSUFFICIENT, c.classify("Ищу работу. Репост: в нашу команду ищем разработчика.").kind)
        assertEquals(PublicationKind.INSUFFICIENT, c.classify("Сейчас не ищу работу разработчиком Java и Spring Boot").kind)
        assertContains(c.classify("Ищу работу разработчиком Java и Spring Boot").reason, "ищу работу")
    }
    @Test fun migrationSevenPreservesArchiveTemplatesAndSavedDecisions() {
        val path = Files.createTempDirectory("recruitment-migration").resolve("research.sqlite")
        Repositories(path).use { repo ->
            seed(repo); repo.saveTemplate(SearchTemplate(name = "Old template", telegramQuery = "java"))
            repo.configureBot(123, setOf(7)); repo.registerBotRecipient(123, 7, 7, 1700000000000)
            repo.decidePublication(123, 7, ResearchMessage(1, source.id, 1700000000, "Synthetic java", source.title, source.username), "SAVED")
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { st ->
            listOf("recruitment_cards", "recruitment_templates", "recruitment_projects", "recruitment_publications").forEach { st.execute("DROP TABLE $it") }; st.execute("PRAGMA user_version=7")
        } }
        Repositories(path).use { repo ->
            assertEquals(1, repo.historyMessages(source.id, 10, null, "java").total); assertEquals("Old template", repo.templates().single().name); assertEquals(1, repo.savedResults().items.size); assertTrue(repo.botRegistered(123, 7))
            assertEquals(1, service(repo).add(service(repo).save(input()).id, AddPublication(source.id, 1)).message.id)
        }
    }
    @Test fun boundedPaginationAndSearchReusesCriteriaWithoutDesiredGate() = runBlocking<Unit> {
        Repositories(Files.createTempDirectory("recruitment-pagination").resolve("research.sqlite")).use { repo ->
            seed(repo); val s = service(repo); val p = s.save(input())
            repo.saveHistoryPage(HistorySyncRecord(source.id, source.username, source.title), (2L..62L).map { TelegramMessage(it, 1700000000, "Java Spring Boot PostgreSQL synthetic publication $it", source.id) })
            (1L..62L).forEach { s.add(p.id, AddPublication(source.id, it)) }
            assertEquals(20, s.cards(p.id, limit = 20).items.size); assertEquals(62, s.cards(p.id).total)
            assertTrue(s.cards(p.id, limit = 20).items.map { it.id }.toSet().intersect(s.cards(p.id, limit = 20, offset = 20).items.map { it.id }.toSet()).isEmpty())
            val found = s.search(p.id, ProjectSearch(query = "java")); assertTrue(found.messages.isNotEmpty()); assertTrue(found.messages.all { it.explanation?.matches == true }); assertTrue(found.cursor.isNotEmpty())
        }
    }
}
