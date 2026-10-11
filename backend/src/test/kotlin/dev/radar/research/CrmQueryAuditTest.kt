package dev.radar.research

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class CrmQueryAuditTest {
    private val source = MonitoredChannel(-1001, "synthetic_jobs", "Synthetic source")
    private fun service(repo: Repositories): RecruitmentProjectService {
        val matcher = MatchingService()
        return RecruitmentProjectService(repo, matcher, SubscriptionService(repo, matcher), RecruiterService(repo, RecruiterSearchPort { error("No network") }))
    }
    @Test fun linkedTemplateMatchesAreUniqueAcrossPagesNotJustInsideOnePage() {
        Repositories(Files.createTempDirectory("crm-match-dedup").resolve("db.sqlite")).use { repo ->
            repo.saveChannel(source); val s = service(repo)
            val a = repo.saveTemplate(SearchTemplate(name = "A", required = listOf("Java"), telegramQuery = "java"))
            val b = repo.saveTemplate(a.copy(id = "", name = "B"))
            val p = s.save(ProjectInput("Synthetic", required = listOf("Java"), sources = listOf(source.id), templateIds = listOf(a.id, b.id)))
            for (t in listOf(a, b)) for (id in 1L..23L) repo.recordMatch(t, ResearchMessage(id, source.id, 1700000000, "Synthetic Java", source.title, source.username))
            val ids = mutableListOf<Long>(); var before = Long.MAX_VALUE
            do {
                val page = s.matches(p.id, limit = 10, before = before)
                ids.addAll(page.messages.map { it.id }); before = page.nextCursor ?: 0
                assertTrue(page.messages.size <= 10)
                if (before > 0) assertEquals(10, page.messages.size)
            } while (before > 0)
            assertEquals((23L downTo 1L).toList(), ids)
        }
    }
    @Test fun deletingLinkedTemplateUpdatesBothDetailAndListAndAllowsEditingListDto() {
        Repositories(Files.createTempDirectory("crm-template-links").resolve("db.sqlite")).use { repo ->
            repo.saveChannel(source); val s = service(repo)
            val template = repo.saveTemplate(SearchTemplate(name = "Synthetic", required = listOf("Java"), telegramQuery = "java"))
            val p = s.save(ProjectInput("Synthetic", sources = listOf(source.id), templateIds = listOf(template.id)))
            repo.deleteTemplate(template.id)
            val listed = s.list().items.single()
            assertEquals(s.get(p.id).criteria.templateIds, listed.criteria.templateIds)
            s.save(listed.criteria.copy(description = "Edited"), p.id)
            assertTrue(s.get(p.id).criteria.templateIds.isEmpty())
        }
    }

    @Test fun cardExplanationUsesItsOwnProjectNotTheLastProjectThatSearchedThePublication() = runBlocking<Unit> {
        val path = Files.createTempDirectory("crm-evidence-scope").resolve("db.sqlite")
        var aId = ""; var bId = ""
        Repositories(path).use { repo ->
            repo.saveChannel(source); val m = MatchingService()
            val s = RecruitmentProjectService(repo, m, SubscriptionService(repo, m), RecruiterService(repo, RecruiterSearchPort {
                SearchPage(listOf(ResearchMessage(1, source.id, 1700000000, "Synthetic Java Kotlin", source.title, source.username)))
            }))
            val a = s.save(ProjectInput("A", required = listOf("Java"), sources = listOf(source.id)))
            val b = s.save(ProjectInput("B", required = listOf("Kotlin"), sources = listOf(source.id))); aId = a.id; bId = b.id
            s.search(a.id, ProjectSearch("telegram", "java")); s.search(b.id, ProjectSearch("telegram", "kotlin"))
            assertEquals(listOf("Java"), s.add(a.id, AddPublication(source.id, 1)).message.explanation!!.foundRequired)
            assertEquals(listOf("Kotlin"), s.add(b.id, AddPublication(source.id, 1)).message.explanation!!.foundRequired)
        }
        Repositories(path).use { repo ->
            val s = service(repo)
            assertEquals(listOf("Java"), s.cards(aId).items.single().message.explanation!!.foundRequired)
            assertEquals(listOf("Kotlin"), s.cards(bId).items.single().message.explanation!!.foundRequired)
        }
    }
    @Test fun staleProjectSnapshotCannotAttachAFalseMatchingExplanation() {
        Repositories(Files.createTempDirectory("crm-stale-add").resolve("db.sqlite")).use { repo ->
            repo.saveChannel(source); val s = service(repo)
            val p = s.save(ProjectInput("Synthetic", required = listOf("Java"), sources = listOf(source.id)))
            val message = ResearchMessage(1, source.id, 1700000000, "Synthetic Java", source.title, source.username)
            repo.rememberRecruitmentResults(p, listOf(message)); s.save(p.criteria.copy(required = listOf("Kotlin")), p.id)
            assertFailsWith<IllegalArgumentException> { repo.addRecruitmentCard(p.id, message, Classification(PublicationKind.INSUFFICIENT, "Synthetic"), p) }
            assertEquals(0, s.statistics(p.id).total)
            val card = s.add(p.id, AddPublication(source.id, 1)) // Explicit manual add can retain a non-match.
            assertFalse(card.message.explanation!!.matches); assertEquals(listOf("Kotlin"), card.message.explanation!!.missingRequired)
        }
    }

}
