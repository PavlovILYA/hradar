package dev.radar.research

import dev.radar.sources.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class LocalProjectTest {
    @Test fun ownerSearchBookmarksAndInboxCanBeAddedWithoutProjectSearch() {
        val path = Files.createTempDirectory("owner-project-evidence").resolve("db")
        var projectId = ""
        Repositories(path).use { repo ->
            val source = repo.saveSource(TelegramSource(chatId = 123, type = SourceType.PRIVATE, title = "Synthetic local"))
            val foreign = repo.saveSource(TelegramSource(chatId = 456, type = SourceType.PRIVATE, title = "Synthetic other"))
            val matching = MatchingService()
            val service = RecruitmentProjectService(repo, matching, SubscriptionService(repo, matching), RecruiterService(repo, RecruiterSearchPort { error("No Telegram reads") }))
            val project = service.save(ProjectInput("Owner evidence", required = listOf("Java"), sourceKeys = listOf(source.key))); projectId = project.id
            fun message(id: Long) = ResearchMessage(id, source.chatId, 2000000000, "Synthetic Java", source.title, null, sourceKey = source.key)
            repo.cacheSearch("fresh", SearchPage(listOf(message(11))))
            repo.saveOwnerBookmark(message(12))
            val rule = repo.saveTemplate(SearchTemplate(name = "Java", required = listOf("Java"), telegramQuery = "Java"))
            val state = repo.configureLocalSubscription(source, rule, null, true, 10, 1)
            assertTrue(repo.commitLocalMonitoring(state, source, rule, state, listOf(message(13)), matching))
            for (id in 11L..13L) {
                val card = service.add(project.id, AddPublication(source.chatId, id, source.key))
                service.update(project.id, card.id, CardUpdate(RecruitmentStage.INTERESTING, "Synthetic note", 4))
                assertEquals(card.id, service.add(project.id, AddPublication(source.chatId, id, source.key)).id)
                assertTrue(card.message.explanation!!.matches)
            }
            repo.cacheSearch("foreign", SearchPage(listOf(message(14).copy(channelId = foreign.chatId, sourceKey = foreign.key))))
            repo.cacheSearch("stale", SearchPage(listOf(message(15))), System.currentTimeMillis() - 86_400_001)
            assertFailsWith<ResourceNotFound> { service.add(project.id, AddPublication(foreign.chatId, 14, foreign.key)) }
            assertFailsWith<ResourceNotFound> { service.add(project.id, AddPublication(source.chatId, 11, foreign.key)) }
            assertFailsWith<ResourceNotFound> { service.add(project.id, AddPublication(source.chatId, 15, source.key)) }
            assertFailsWith<ResourceNotFound> { service.add(project.id, AddPublication(source.chatId, 999, source.key)) }
            assertTrue(repo.channels().isEmpty()); assertTrue(repo.savedResults().items.isEmpty()); assertTrue(repo.notifications().isEmpty())
            repo.deleteSource(source.key)
            assertFailsWith<ResourceNotFound> { service.add(project.id, AddPublication(source.chatId, 12, source.key)) }
        }
        Repositories(path).use { repo ->
            val cards = repo.recruitmentCards(projectId, 25, 0, null, null, "", "", "updated").items
            assertEquals(3, cards.size)
            assertTrue(cards.all { it.notes == "Synthetic note" && it.interest == 4 && it.stage == RecruitmentStage.INTERESTING })
        }
    }

    @Test fun ownerEvidenceCannotCrossProjectTopicScopesOrChangedCriteria() {
        Repositories(Files.createTempDirectory("owner-project-topic").resolve("db")).use { repo ->
            val source = repo.saveSource(TelegramSource(chatId = 123, type = SourceType.SUPERGROUP, title = "Synthetic forum", isForum = true, scope = SourceScope(ScopeKind.FORUM_TOPIC, forumTopicId = 10)))
            val matching = MatchingService()
            val service = RecruitmentProjectService(repo, matching, SubscriptionService(repo, matching), RecruiterService(repo, RecruiterSearchPort { SearchPage() }))
            val project = service.save(ProjectInput("Topic", sourceKeys = listOf(source.key)))
            val invalid = ResearchMessage(1, source.chatId, 100, "Synthetic Java", source.title, null, sourceKey = source.key, forumTopicId = 20)
            repo.cacheSearch("wrong-topic", SearchPage(listOf(invalid)))
            assertFailsWith<ResourceNotFound> { service.add(project.id, AddPublication(source.chatId, 1, source.key)) }
            repo.saveOwnerBookmark(invalid.copy(id = 2, forumTopicId = 10))
            val card = service.add(project.id, AddPublication(source.chatId, 2, source.key))
            service.save(project.criteria.copy(name = "Changed"), project.id)
            assertFailsWith<IllegalArgumentException> { repo.addRecruitmentCard(project.id, card.message, card.classification, project) }
            service.save(service.get(project.id).criteria.copy(status = ProjectStatus.ARCHIVED), project.id)
            assertFailsWith<IllegalArgumentException> { service.add(project.id, AddPublication(source.chatId, 2, source.key)) }
        }
    }

    @Test fun mixedProjectCanAddExistingLegacyArchiveWithoutNewSearch() {
        Repositories(Files.createTempDirectory("mixed-evidence").resolve("research.sqlite")).use { repo ->
            val public = repo.saveChannel(MonitoredChannel(-1001, "synthetic_jobs", "Synthetic public"))
            repo.saveHistoryPage(dev.radar.history.HistorySyncRecord(public.id, public.username, public.title), listOf(dev.radar.telegram.TelegramMessage(10, 100, "Synthetic Java public publication", public.id)))
            val source = repo.saveSource(TelegramSource(chatId = 123, type = SourceType.PRIVATE, title = "Synthetic private"))
            val matching = MatchingService()
            val service = RecruitmentProjectService(repo, matching, SubscriptionService(repo, matching), RecruiterService(repo, RecruiterSearchPort { SearchPage() }))
            val project = service.save(ProjectInput("Mixed", sources = listOf(public.id), sourceKeys = listOf(source.key)))
            assertEquals(10L, service.add(project.id, AddPublication(public.id, 10)).message.id)
            val favorite = ResearchMessage(11, public.id, 100, "Synthetic Java favorite", public.title, public.username)
            repo.configureBot(1, setOf(2)); repo.registerBotRecipient(1, 2, 2, 1)
            repo.decidePublication(1, 2, favorite, "SAVED")
            assertEquals(11L, service.add(project.id, AddPublication(public.id, 11)).message.id)
            val template = repo.saveTemplate(SearchTemplate(name = "Synthetic match", telegramQuery = "Java"))
            assertTrue(repo.recordMatch(template, favorite.copy(id = 12)))
            assertEquals(12L, service.add(project.id, AddPublication(public.id, 12)).message.id)
            val foreign = repo.saveChannel(MonitoredChannel(-1002, "synthetic_other", "Synthetic other"))
            repo.decidePublication(1, 2, favorite.copy(channelId = foreign.id), "SAVED")
            assertFailsWith<ResourceNotFound> { service.add(project.id, AddPublication(foreign.id, 11)) }
            assertFailsWith<ResourceNotFound> { service.add(project.id, AddPublication(public.id, 10, source.key)) }
        }
    }
    @Test fun subscribedLegacyProjectCanSwitchToLocalSourceWithoutMonitoring() {
        Repositories(Files.createTempDirectory("local-switch").resolve("research.sqlite")).use { repo ->
            repo.saveChannel(MonitoredChannel(-1001, "synthetic_jobs", "Synthetic public"))
            val local = repo.saveSource(TelegramSource(chatId = 123, type = SourceType.PRIVATE, title = "Synthetic local"))
            val matching = MatchingService()
            val service = RecruitmentProjectService(repo, matching, SubscriptionService(repo, matching), RecruiterService(repo, RecruiterSearchPort { SearchPage() }))
            val project = service.save(ProjectInput("Java", required = listOf("Java"), sources = listOf(-1001)))
            val subscription = service.subscribe(project.id)
            val changed = service.save(service.get(project.id).criteria.copy(sources = emptyList(), sourceKeys = listOf(local.key)), project.id)
            assertEquals(listOf(local.key), changed.criteria.sourceKeys)
            assertFalse(repo.template(subscription.id)!!.active)
            assertFalse(repo.template(subscription.id)!!.subscribed)
        }
    }
    @Test fun personalChatEvidenceCardsAndRestartStayLocal() = runBlocking {
        val path = Files.createTempDirectory("local-project").resolve("research.sqlite")
        var projectId = ""; var cardId = ""
        Repositories(path).use { repo ->
            val source = repo.saveSource(TelegramSource(chatId = 123, type = SourceType.PRIVATE, title = "Synthetic cloud chat"))
            val local = LocalProjectSearchService(repo, RecruiterSearchPort { input ->
                assertEquals(listOf(source.key), input.sourceKeys)
                SearchPage(listOf(ResearchMessage(10, 123, 100, "Ищу работу Java разработчиком", source.title, null, sourceKey = source.key)))
            })
            val matching = MatchingService()
            val service = RecruitmentProjectService(repo, matching, SubscriptionService(repo, matching), RecruiterService(repo, RecruiterSearchPort { error("Public port must stay separate") }), localSearch = local)
            val project = service.save(ProjectInput("Java", required = listOf("Java"), sourceKeys = listOf(source.key))); projectId = project.id
            val result = service.search(project.id, ProjectSearch("telegram", "java"))
            assertEquals(1, result.messages.size)
            val card = service.add(project.id, AddPublication(123, 10)); cardId = card.id
            service.update(project.id, card.id, CardUpdate(RecruitmentStage.INTERESTING, "Synthetic note", 4))
            assertEquals(card.id, service.add(project.id, AddPublication(123, 10)).id)
            assertFailsWith<IllegalArgumentException> { service.subscribe(project.id) }
            assertTrue(repo.channels().isEmpty()); assertTrue(repo.historyMessages(123, 50, null, "").messages.isEmpty())
            assertTrue(repo.savedResults().items.isEmpty()); assertTrue(repo.notifications().isEmpty())
            assertNull(repo.recruitmentPublication(123, 10))
            val other = service.save(ProjectInput("Other", sourceKeys = listOf(source.key)))
            assertFailsWith<ResourceNotFound> { service.add(other.id, AddPublication(123, 10)) }
            repo.deleteSource(source.key)
            assertFailsWith<ResourceNotFound> { service.add(project.id, AddPublication(123, 10)) }
        }
        Repositories(path).use { repo ->
            val card = repo.recruitmentCards(projectId, 25, 0, null, null, "", "", "updated").items.single()
            assertEquals(cardId, card.id); assertEquals("Synthetic note", card.notes); assertEquals(4, card.interest)
            assertEquals(RecruitmentStage.INTERESTING, card.stage)
        }
    }
}
