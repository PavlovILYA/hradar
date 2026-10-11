package dev.radar.research

import dev.radar.telegram.TdlibGateway
import kotlinx.coroutines.*
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

class CrmMonitoringAuditTest {
    private val source = MonitoredChannel(-1001, "synthetic_jobs", "Synthetic")
    private fun service(repo: Repositories): RecruitmentProjectService {
        val m = MatchingService(); return RecruitmentProjectService(repo, m, SubscriptionService(repo, m), RecruiterService(repo, RecruiterSearchPort { error("No search") }))
    }
    @Test fun archivedOrEditedOwnedTemplateCannotCommitAnOldMatchSnapshot() {
        for (archive in listOf(true, false)) {
            Repositories(Files.createTempDirectory("crm-monitor-snapshot").resolve("db.sqlite")).use { repo ->
                repo.saveChannel(source); val s = service(repo)
                val p = s.save(ProjectInput("Synthetic", required = listOf("Java"), sources = listOf(source.id)))
                val snapshot = s.subscribe(p.id)
                val current = s.get(p.id)
                s.save(if (archive) current.criteria.copy(status = ProjectStatus.ARCHIVED) else current.criteria.copy(required = listOf("Kotlin")), p.id)
                val publication = ResearchMessage(1, source.id, (System.currentTimeMillis() / 1000 + 10).toInt(), "Synthetic Java", source.title, source.username)
                assertFalse(repo.recordMatch(snapshot, publication), "An in-flight old rule must not write after project archive/edit")
                assertFalse(repo.recordMatch(snapshot, publication, monitoring = MatchingService()))
                assertTrue(repo.notifications().isEmpty())
            }
        }
    }
    @Test fun monitoredWriteRechecksCurrentRulesRatherThanLosingAStillEligiblePublication() {
        Repositories(Files.createTempDirectory("crm-monitor-recheck").resolve("db.sqlite")).use { repo ->
            repo.saveChannel(source); val s = service(repo)
            val p = s.save(ProjectInput("Synthetic", required = listOf("Java"), sources = listOf(source.id)))
            val snapshot = s.subscribe(p.id); s.save(s.get(p.id).criteria.copy(required = listOf("Kotlin")), p.id)
            val publication = ResearchMessage(1, source.id, (System.currentTimeMillis() / 1000 + 10).toInt(), "Synthetic Kotlin", source.title, source.username)
            assertTrue(repo.recordMatch(snapshot, publication, monitoring = MatchingService()))
            assertEquals(listOf("Kotlin"), repo.notifications().single().message.explanation!!.foundRequired)
            repo.deleteChannel(source.id)
            assertFalse(repo.recordMatch(snapshot, publication.copy(id = 2), monitoring = MatchingService()))
            assertEquals(1, repo.notifications().size) // Old evidence survives source removal.
        }
    }
    @Test fun removingASourceDuringRecoveryStopsReadsAndDoesNotCrashOrCreateOrphanState() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("crm-monitor-remove")
        Repositories(dir.resolve("db.sqlite")).use { repo ->
            repo.saveChannel(source); repo.saveState(MonitoringState(source.id, 1))
            repo.saveTemplate(SearchTemplate(name = "All sources", required = listOf("Java"), telegramQuery = "java", sourceMode = "all", subscribed = true))
            val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>(); var calls = 0
            val gateway = TdlibGateway(1, "unused", dir); val m = MatchingService()
            val monitor = MonitoringService(gateway, repo, SearchService(gateway, repo, m), NotificationService(repo, m), { _, _, _ ->
                calls++; entered.complete(Unit); resume.await()
                TdApi.Messages().apply { messages = arrayOf(TdApi.Message().apply {
                    id = 2; chatId = source.id; date = (System.currentTimeMillis() / 1000 + 10).toInt()
                    content = TdApi.MessageText().apply { text = TdApi.FormattedText("Synthetic Java", emptyArray()) }
                }) }
            }, { true }, 0)
            try {
                supervisorScope {
                    val pending = async { monitor.pollOnce() }
                    entered.await(); repo.deleteChannel(source.id); resume.complete(Unit); pending.await()
                }
                assertEquals(1, calls); assertTrue(repo.notifications().isEmpty()); assertNull(repo.state(source.id))
                repo.saveState(MonitoringState(source.id, 99)) // Late state commits must be harmless too.
                assertNull(repo.state(source.id))
            } finally { monitor.close() }
        }
    }
}
