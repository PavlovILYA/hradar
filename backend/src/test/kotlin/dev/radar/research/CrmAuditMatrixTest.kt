package dev.radar.research

import dev.radar.history.HistorySyncRecord
import dev.radar.history.SyncStatus
import dev.radar.history.PauseReason
import dev.radar.telegram.TelegramMessage
import kotlinx.coroutines.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class CrmAuditMatrixTest {
    private val source = MonitoredChannel(-1001, "synthetic_jobs", "Synthetic")
    private fun service(repo: Repositories, port: RecruiterSearchPort = RecruiterSearchPort { error("No network") }): RecruitmentProjectService {
        val m = MatchingService(); return RecruitmentProjectService(repo, m, SubscriptionService(repo, m), RecruiterService(repo, port))
    }
    private fun message(id: Long, sourceId: Long = source.id) = ResearchMessage(id, sourceId, 1700000000, "Synthetic Java Spring Boot PostgreSQL", source.title, source.username)
    @Test fun tenArchiveSourcesMixedCriteriaAndSmallRemainingPagesHaveNoLossOrDuplicates() = runBlocking<Unit> {
        Repositories(Files.createTempDirectory("crm-ten-sources").resolve("db.sqlite")).use { repo ->
            val sources = (1L..10L).map { MonitoredChannel(-1000 - it, "synthetic_$it", "Synthetic $it") }
            sources.forEach { ch ->
                repo.saveChannel(ch)
                repo.saveHistoryPage(HistorySyncRecord(ch.id, ch.username, ch.title), (1L..31L).map {
                    TelegramMessage(it, 1700000000, when { it % 7 == 0L -> "Java Spring Boot junior"; it % 3 == 0L -> "Java"; else -> "Java Spring Boot" }, ch.id)
                })
            }
            val s = service(repo); val p = s.save(ProjectInput("Synthetic", required = listOf("Java", "Spring Boot"), excluded = listOf("junior"), sources = sources.map { it.id }))
            val expected = sources.flatMap { ch -> (1L..31L).filter { it % 7 != 0L && it % 3 != 0L }.map { ch.id to it } }.toSet()
            val found = mutableListOf<Pair<Long, Long>>(); var cursor = ""; var steps = 0
            do {
                val page = s.search(p.id, ProjectSearch(query = "java", cursor = cursor))
                assertTrue(page.messages.size <= 10 && page.scanned <= 50 && page.pagesScanned <= 5)
                found.addAll(page.messages.map { it.channelId to it.id }); cursor = page.cursor
                assertTrue(++steps <= 100, "Traversal must terminate")
            } while (cursor.isNotEmpty())
            assertEquals(expected, found.toSet()); assertEquals(expected.size, found.size)
        }
    }
    @Test fun replayAndConcurrentContinuationRequestsKeepTheSameNextPage() = runBlocking<Unit> {
        Repositories(Files.createTempDirectory("crm-replay").resolve("db.sqlite")).use { repo ->
            repo.saveChannel(source)
            val s = service(repo, RecruiterSearchPort { req ->
                val before = req.cursor.toLongOrNull() ?: 46L
                val ids = (45L downTo 1L).filter { it < before }.take(req.limit)
                SearchPage(ids.map { message(it) }, if (ids.last() == 1L) "" else ids.last().toString())
            }); val p = s.save(ProjectInput("Synthetic", required = listOf("Java"), sources = listOf(source.id)))
            val first = s.search(p.id, ProjectSearch("telegram", "java"))
            val request = ProjectSearch("telegram", "java", first.cursor)
            val a = async { s.search(p.id, request) }; val b = async { s.search(p.id, request) }
            val second = a.await(); assertEquals(second.messages, b.await().messages)
            assertEquals(second.messages, s.search(p.id, request).messages)
            val third = s.search(p.id, request.copy(cursor = second.cursor))
            assertTrue((first.messages + second.messages).map { it.id }.intersect(third.messages.map { it.id }.toSet()).isEmpty())
        }
    }
    @Test fun sourceOrCriteriaChangeDuringSuspensionDoesNotPersistStaleOnlineEvidence() = runBlocking<Unit> {
        for (removeSource in listOf(false, true)) {
            Repositories(Files.createTempDirectory("crm-inflight-change").resolve("db.sqlite")).use { repo ->
                repo.saveChannel(source); val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
                val s = service(repo, RecruiterSearchPort { entered.complete(Unit); resume.await(); SearchPage(listOf(message(99))) })
                val p = s.save(ProjectInput("Synthetic", required = listOf("Java"), sources = listOf(source.id)))
                supervisorScope {
                    val pending = async { s.search(p.id, ProjectSearch("telegram", "java")) }
                    entered.await()
                    if (removeSource) repo.deleteChannel(source.id) else s.save(p.criteria.copy(required = listOf("Kotlin")), p.id)
                    resume.complete(Unit); assertFailsWith<IllegalArgumentException> { pending.await() }
                }
                assertNull(repo.recruitmentPublication(source.id, 99))
            }
        }
    }
    @Test fun unicodeNormalizationAndOrNotPhrasesAndEmptyTextRemainDeterministic() {
        val m = MatchingService(); val t = SearchTemplate(name = "Synthetic", telegramQuery = "java", required = listOf("Java", "Spring Boot"), any = listOf("Kafka", "Docker"), excluded = listOf("стажёр", "junior"))
        val value = m.check(t, "ＪＡＶＡ\nSpring\u00a0Boot Docker")
        assertTrue(value.matches); assertEquals(listOf("Java", "Spring Boot"), value.foundRequired); assertEquals(listOf("Docker"), value.foundAny)
        assertFalse(m.check(t, "Java Spring Boot Kafka стажер").matches)
        assertFalse(m.check(t, "JavaScript Spring Boot Kafka").matches)
        assertFalse(m.check(t, "Java Spring Boot").matches)
        assertFalse(m.check(t, null).matches); assertFalse(m.check(t.copy(required = emptyList(), any = emptyList()), "   ").matches)
    }
    @Test fun v1ThroughV7MigrationsPreserveOldRecordsAndRemainRestartable() {
        for (version in 1..7) {
            val path = Files.createTempDirectory("crm-v$version").resolve("db.sqlite")
            var template: SearchTemplate? = null
            Repositories(path).use { repo ->
                repo.saveChannel(source)
                template = repo.saveTemplate(SearchTemplate(name = "Legacy", required = listOf("Java"), telegramQuery = "java", sourceMode = "selected", sources = listOf(source.id), subscribed = true))
                repo.recordMatch(template!!, message(5)); repo.markRead(repo.notifications().single().id)
                repo.saveState(MonitoringState(source.id, 5, 3, 5, retryAt = 1700000300))
                if (version >= 2) repo.saveHistoryPage(HistorySyncRecord(source.id, source.username, source.title,
                    status = if (version >= 3) SyncStatus.PAUSED else SyncStatus.FAILED, cursor = 5,
                    retryCount = 2, nextRetryAt = 1700000300, maxMessages = 5000,
                    pauseReason = if (version >= 3) PauseReason.USER else null), (1L..5L).map { TelegramMessage(it, 1700000000, "Synthetic Java archive", source.id) })
                if (version >= 5) { repo.configureBot(123, setOf(7)); repo.registerBotRecipient(123, 7, 7, 1700000000000) }
                if (version >= 7) repo.decidePublication(123, 7, message(5), "SAVED")
            }
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { st ->
                listOf("telegram_sources", "bot_access", "bot_invitations").forEach { st.execute("DROP TABLE $it") }
                listOf("recruitment_cards", "recruitment_templates", "recruitment_projects", "recruitment_publications").forEach { st.execute("DROP TABLE $it") }
                if (version < 7) {
                    listOf("bot_workspace", "publication_decisions").forEach { st.execute("DROP TABLE $it") }
                    st.execute("DROP INDEX matches_date"); st.execute("ALTER TABLE matched_messages DROP COLUMN message_date")
                }
                if (version < 6) listOf("bot_publish_request", "bot_publish_permission").forEach { st.execute("DROP TABLE $it") }
                if (version < 5) listOf("bot_outbox", "bot_recipients", "bot_state").forEach { st.execute("DROP TABLE $it") }
                if (version < 4) listOf("mcp_settings", "mcp_sources", "mcp_audit").forEach { st.execute("DROP TABLE $it") }
                if (version < 2) listOf("imported_messages", "history_sync").forEach { st.execute("DROP TABLE $it") }
                else if (version == 2) {
                    st.execute("ALTER TABLE history_sync RENAME TO newer_history")
                    st.execute("CREATE TABLE history_sync(channel_id INTEGER PRIMARY KEY,username TEXT NOT NULL UNIQUE COLLATE NOCASE,status TEXT NOT NULL CHECK(status IN ('IDLE','RUNNING','COMPLETED','FAILED')),data TEXT NOT NULL)")
                    st.execute("INSERT INTO history_sync SELECT * FROM newer_history"); st.execute("DROP TABLE newer_history")
                }
                st.execute("PRAGMA user_version=$version")
            } }
            repeat(2) { restart ->
                Repositories(path).use { repo ->
                    assertEquals(template, repo.template(template!!.id)); assertEquals(1, repo.notifications().size); assertEquals(0, repo.unreadCount())
                    assertEquals(MonitoringState(source.id, 5, 3, 5, retryAt = 1700000300), repo.state(source.id))
                    assertEquals(if (version >= 2) 5 else 0, repo.historyMessages(source.id, 10, null, "java").total)
                    if (version >= 2) {
                        val history = repo.historySync(source.id)!!
                        assertEquals(5, history.cursor); assertEquals(2, history.retryCount); assertEquals(5000, history.maxMessages)
                        assertEquals(if (version >= 3) SyncStatus.PAUSED else SyncStatus.FAILED, history.status)
                    }
                    if (version >= 5) assertTrue(repo.botRegistered(123, 7))
                    if (version >= 7) assertEquals(1, repo.savedResults().items.size)
                    assertEquals(1, repo.channels().size)
                    val s = service(repo)
                    if (restart == 0) {
                        val p = s.save(ProjectInput("Post-migration", required = listOf("Java"), sources = listOf(source.id), templateIds = listOf(template!!.id)))
                        val c = s.add(p.id, AddPublication(source.id, 5)); s.update(p.id, c.id, CardUpdate(RecruitmentStage.CONTACTED, "Post-migration note", 4))
                        val owned = s.subscribe(p.id); s.save(s.get(p.id).criteria.copy(status = ProjectStatus.ARCHIVED), p.id)
                        assertFalse(repo.template(owned.id)!!.active); assertTrue(repo.template(template!!.id)!!.active)
                    } else {
                        val p = s.list(status = ProjectStatus.ARCHIVED).items.single(); val c = s.cards(p.id).items.single()
                        assertEquals("Post-migration note", c.notes); assertEquals(RecruitmentStage.CONTACTED, c.stage)
                        assertFailsWith<IllegalArgumentException> { s.update(p.id, c.id, CardUpdate(RecruitmentStage.NEW)) }
                        assertFailsWith<IllegalArgumentException> { s.remove(p.id, c.id) }
                        assertFalse(repo.template(recruitmentSubscriptionId(p.id))!!.active)
                    }
                }
            }
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { st ->
                assertEquals(10, st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) })
                assertEquals("ok", st.executeQuery("PRAGMA integrity_check").use { it.next(); it.getString(1) })
                assertFalse(st.executeQuery("PRAGMA foreign_key_check").use { it.next() })
            } }
        }
    }
    @Test fun failedV8DdlRollsBackAndCanRecoverWithoutErasingOldTables() {
        val path = Files.createTempDirectory("crm-migration-rollback").resolve("db.sqlite")
        Repositories(path).use { repo -> repo.saveChannel(source); repo.saveTemplate(SearchTemplate(name = "Legacy", required = listOf("Java"), telegramQuery = "java")) }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { st ->
            listOf("telegram_sources", "bot_access", "bot_invitations").forEach { st.execute("DROP TABLE $it") }
            listOf("recruitment_cards", "recruitment_templates", "recruitment_projects", "recruitment_publications").forEach { st.execute("DROP TABLE $it") }
            st.execute("PRAGMA user_version=7"); st.execute("CREATE VIEW recruitment_cards AS SELECT id FROM search_templates")
        } }
        assertFailsWith<java.sql.SQLException> { Repositories(path).close() }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { st ->
            assertEquals(7, st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) })
            assertEquals(0, st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('recruitment_projects','recruitment_templates')").use { it.next(); it.getInt(1) })
            st.execute("DROP VIEW recruitment_cards")
        } }
        Repositories(path).use { repo -> assertEquals("Legacy", repo.templates().single().name); assertEquals(source, repo.channels().single()) }
    }
    @Test fun fiftyThousandArchiveAndCardRecordsHaveBoundedPagesAndStableTies() = runBlocking<Unit> {
        for (size in listOf(10_000, 50_000)) {
            val path = Files.createTempDirectory("crm-scale-$size").resolve("db.sqlite")
            Repositories(path).use { repo ->
                repo.saveChannel(source); val s = service(repo); val p = s.save(ProjectInput("Synthetic", required = listOf("Java"), sources = listOf(source.id)))
                repo.saveHistoryPage(HistorySyncRecord(source.id, source.username, source.title), listOf(TelegramMessage(1, 1700000000, "Synthetic Java Spring Boot PostgreSQL", source.id)))
                s.add(p.id, AddPublication(source.id, 1))
                DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { st ->
                    st.execute("""WITH RECURSIVE numbers(n) AS (SELECT 2 UNION ALL SELECT n+1 FROM numbers WHERE n<$size)
                        INSERT INTO imported_messages SELECT -1001,n,1700000000,'Synthetic Java Spring Boot PostgreSQL','synthetic java spring boot postgresql' FROM numbers""")
                    st.execute("""WITH RECURSIVE numbers(n) AS (SELECT 2 UNION ALL SELECT n+1 FROM numbers WHERE n<$size)
                        INSERT INTO recruitment_cards SELECT 'synthetic-'||n,project_id,channel_id,n,stage,interest,normalized_text,normalized_notes,json_set(data,'$.id','synthetic-'||n,'$.message.id',n),created_at,updated_at FROM recruitment_cards,numbers WHERE message_id=1""")
                } }
                val start = System.nanoTime()
                for (sort in listOf("updated", "oldest", "interest")) {
                    val a = s.cards(p.id, 20, 0, sort = sort); val b = s.cards(p.id, 20, 20, sort = sort)
                    assertEquals(size, a.total); assertEquals(20, a.items.size); assertEquals(20, b.items.size)
                    assertTrue(a.items.map { it.id }.intersect(b.items.map { it.id }.toSet()).isEmpty())
                }
                assertEquals(0, s.cards(p.id, query = "missing").total); assertEquals(size, s.statistics(p.id).total)
                val page = s.search(p.id, ProjectSearch(query = "java")); val next = s.search(p.id, ProjectSearch(query = "java", cursor = page.cursor))
                assertEquals(10, page.messages.size); assertEquals(10, next.messages.size); assertEquals(1, page.pagesScanned)
                assertTrue(page.messages.map { it.id }.intersect(next.messages.map { it.id }.toSet()).isEmpty())
                assertEquals(size.toLong(), repo.recruitmentArchiveSize(listOf(source.id)))
                println("CRM scale $size: six 20-card pages + filters/stats + two search pages = ${(System.nanoTime() - start) / 1_000_000}ms")
            }
        }
    }

    @Test fun fiftyThousandLinkedPublicationMatchesRemainBoundedAndDeduplicateBeforeCursor() {
        val path = Files.createTempDirectory("crm-linked-scale").resolve("db.sqlite")
        Repositories(path).use { repo ->
            repo.saveChannel(source); val s = service(repo)
            val a = repo.saveTemplate(SearchTemplate(name = "Synthetic A", required = listOf("Java"), telegramQuery = "java"))
            val b = repo.saveTemplate(a.copy(id = "", name = "Synthetic B"))
            val p = s.save(ProjectInput("Synthetic", sources = listOf(source.id), templateIds = listOf(a.id, b.id)))
            for (t in listOf(a, b)) {
                repo.recordMatch(t, message(1))
                DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { st ->
                    st.execute("""WITH RECURSIVE numbers(n) AS (SELECT 2 UNION ALL SELECT n+1 FROM numbers WHERE n<50000)
                        INSERT INTO matched_messages SELECT '${t.id}-'||n,template_id,channel_id,n,json_set(data,'$.id','${t.id}-'||n,'$.message.id',n),message_date FROM matched_messages,numbers WHERE template_id='${t.id}' AND message_id=1""")
                    st.execute("INSERT INTO notifications SELECT id,0,1700000000000 FROM matched_messages WHERE template_id='${t.id}' AND message_id>1 ORDER BY message_id")
                } }
            }
            val start = System.nanoTime()
            val first = s.matches(p.id, 20); val next = s.matches(p.id, 20, first.nextCursor!!)
            assertEquals((50000L downTo 49981L).toList(), first.messages.map { it.id })
            assertEquals((49980L downTo 49961L).toList(), next.messages.map { it.id })
            println("CRM linked scale 50000 publications / 100000 notifications: two 20-result pages = ${(System.nanoTime() - start) / 1_000_000}ms")
        }
    }

}
