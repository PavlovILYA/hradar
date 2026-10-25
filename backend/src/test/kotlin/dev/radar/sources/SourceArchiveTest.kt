package dev.radar.sources

import dev.radar.history.*
import dev.radar.research.*
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class SourceArchiveTest {
    private fun path() = Files.createTempDirectory("source-archive-").resolve("db.sqlite")
    private fun forum(repo: Repositories, topic: Int? = null) = repo.saveSource(TelegramSource(
        chatId = -2001, type = SourceType.SUPERGROUP, title = "Synthetic forum", isForum = true,
        scope = if (topic == null) SourceScope() else SourceScope(ScopeKind.FORUM_TOPIC, topic),
    ))
    private fun message(source: TelegramSource, id: Long, topic: Int? = null, text: String = "Synthetic Java caption") = ResearchMessage(
        id, source.chatId, 1700000000, text, source.title, null, sourceKey = source.key, forumTopicId = topic,
    )
    @Test fun sharedPublicationIdentityScopedMembershipEditsAndNoPublicLeak() {
        Repositories(path()).use { repo ->
            val whole = forum(repo); val one = forum(repo, 1); val two = forum(repo, 2)
            val a = repo.beginSourceSync(whole.key); val b = repo.beginSourceSync(one.key); val c = repo.beginSourceSync(two.key)
            repo.saveSourcePage(a.copy(cursor = 8), listOf(message(whole, 10, 1), message(whole, 8, 2)))
            repo.saveSourcePage(b.copy(cursor = 10), listOf(message(one, 10, 1)))
            repo.saveSourcePage(b.copy(cursor = 10), listOf(message(one, 10, 1, "Edited synthetic Java caption")))
            repo.saveSourcePage(c.copy(cursor = 8), listOf(message(two, 8, 2)))
            assertEquals(2, repo.sourceArchive(whole.key).total)
            assertEquals(listOf(10L), repo.sourceArchive(one.key).messages.map { it.id })
            assertEquals(listOf(8L), repo.sourceArchive(two.key).messages.map { it.id })
            assertEquals("Edited synthetic Java caption", repo.sourceArchive(whole.key).messages.first().text)
            assertEquals(1, repo.sourceSync(one.key)!!.messageCount)
            assertTrue(repo.channels().isEmpty()); assertTrue(repo.historySyncs().isEmpty())
            assertEquals(0, repo.historyMessages(whole.chatId, 50, null, "").total)
            assertTrue(repo.mcpSources().isEmpty()); assertTrue(repo.notifications().isEmpty())
            assertNull(repo.recruitmentPublication(whole.chatId, 10))
            assertFailsWith<IllegalArgumentException> { repo.saveSourcePage(b, listOf(message(two, 8, 2))) }
        }
    }
    @Test fun movingPublicationToAnotherTopicInvalidatesOldTopicMembership() {
        Repositories(path()).use { repo ->
            val whole = forum(repo); val one = forum(repo, 1); val two = forum(repo, 2)
            val topicState = repo.saveSourcePage(repo.beginSourceSync(one.key).copy(cursor = 10, status = SyncStatus.PAUSED, pauseReason = PauseReason.LIMIT), listOf(message(one, 10, 1)))
            assertEquals(1, topicState.messageCount)
            repo.saveSourcePage(repo.beginSourceSync(whole.key), listOf(message(whole, 10, 2)))
            val afterMove = repo.sourceSync(one.key)!!
            assertEquals(0, afterMove.messageCount)
            assertEquals(topicState.cursor, afterMove.cursor)
            assertEquals(topicState.status, afterMove.status)
            assertEquals(topicState.pauseReason, afterMove.pauseReason)
            assertEquals(0, repo.sourceArchive(one.key).total)
            assertTrue(repo.sourceArchive(one.key).messages.isEmpty())
            assertEquals(2, repo.sourceArchive(whole.key).messages.single().forumTopicId)
            repo.saveSourcePage(repo.beginSourceSync(two.key), listOf(message(two, 10, 2)))
            assertEquals(listOf(10L), repo.sourceArchive(two.key).messages.map { it.id })
        }
    }
    @Test fun keyRemovalDuringFetchDoesNotRecreateOrPersistLateBodies() {
        Repositories(path()).use { repo ->
            val source = forum(repo); val state = repo.beginSourceSync(source.key)
            repo.deleteSource(source.key)
            repo.saveSourcePage(state, listOf(message(source, 5)))
            assertNull(repo.sourceSync(source.key)); assertTrue(repo.sourceSyncs().isEmpty())
        }
    }
    @Test fun archivePagesAreBoundedLiteralAndStableWithCrossChatMessageIds() {
        Repositories(path()).use { repo ->
            val a = forum(repo); val b = repo.saveSource(TelegramSource(chatId = 123, type = SourceType.PRIVATE, title = "Synthetic cloud"))
            repo.saveSourcePage(repo.beginSourceSync(a.key), (1L..5).map { message(a, it) })
            repo.saveSourcePage(repo.beginSourceSync(b.key), listOf(message(b, 5, text = "Other synthetic")))
            val first = repo.sourceArchive(a.key, 2, query = "java")
            assertEquals(listOf(5L, 4L), first.messages.map { it.id }); assertEquals(4L, first.nextCursor)
            assertEquals(listOf(3L, 2L), repo.sourceArchive(a.key, 2, first.nextCursor, "JAVA").messages.map { it.id })
            assertEquals(0, repo.sourceArchive(a.key, query = "%").total)
            assertEquals("Other synthetic", repo.sourceArchive(b.key).messages.single().text)
        }
    }
    @Test fun shortInclusivePagesDeduplicateAndResumeAfterRestart() = runBlocking {
        val db = path(); var key = ""
        Repositories(db).use { repo ->
            val source = forum(repo); key = source.key
            val state = repo.beginSourceSync(key)
            repo.saveSourcePage(state.copy(cursor = 4), listOf(message(source, 5), message(source, 4)))
        }
        Repositories(db).use { repo ->
            val source = repo.sources().single()
            val cursors = mutableListOf<Long>()
            SourceArchiveService(repo, { _, cursor ->
                cursors += cursor
                when (cursor) {
                    4L -> SourceMessagePage(listOf(message(source, 4), message(source, 3)), 3)
                    3L -> SourceMessagePage(listOf(message(source, 2)), 2)
                    else -> SourceMessagePage(emptyList(), null)
                }
            }, { true }, pageDelayMs = 0, requestIntervalMs = 0).use { sync ->
                sync.resumePending()
                withTimeout(5000) { while (repo.sourceSync(key)?.status != SyncStatus.COMPLETED) delay(10) }
            }
            assertEquals(listOf(4L, 3L, 2L), cursors)
            assertEquals(4, repo.sourceArchive(key).total)
            assertEquals(4, repo.sourceSync(key)!!.messageCount)
        }
    }
    @Test fun progressedMessagesSurviveALaterInclusiveCursorStall() = runBlocking {
        Repositories(path()).use { repo ->
            val source = forum(repo)
            val seen = mutableListOf<Long>()
            SourceArchiveService(repo, { _, cursor ->
                seen += cursor
                if (cursor == 0L) SourceMessagePage(listOf(message(source, 10), message(source, 9)), 9, state = "cursor_stalled")
                else SourceMessagePage(emptyList(), null)
            }, { true }, pageDelayMs = 0, retryDelayMs = 0, requestIntervalMs = 0).use { sync ->
                sync.start(source.key)
                withTimeout(5000) { while (repo.sourceSync(source.key)!!.status == SyncStatus.RUNNING) delay(10) }
                assertEquals(SyncStatus.COMPLETED, repo.sourceSync(source.key)!!.status)
                assertEquals(listOf(0L, 9L), seen)
                assertEquals(listOf(10L, 9L), repo.sourceArchive(source.key).messages.map { it.id })
            }
        }
    }
    @Test fun pauseCancelsInflightAndAccessLossRetainsCursor() = runBlocking {
        Repositories(path()).use { repo ->
            val source = forum(repo); val started = CompletableDeferred<Unit>()
            SourceArchiveService(repo, { _, _ -> started.complete(Unit); awaitCancellation() }, { true }, requestIntervalMs = 0).use { sync ->
                sync.start(source.key); started.await(); sync.stop(source.key)
                assertEquals(SyncStatus.PAUSED, repo.sourceSync(source.key)!!.status)
                assertEquals(PauseReason.USER, repo.sourceSync(source.key)!!.pauseReason)
            }
            SourceArchiveService(repo, { _, _ -> throw TelegramRequestException(403, "SYNTHETIC_ACCESS_DENIED") }, { true }, requestIntervalMs = 0).use { sync ->
                sync.start(source.key)
                withTimeout(5000) { while (repo.sourceSync(source.key)!!.status != SyncStatus.FAILED) delay(10) }
                assertEquals(0L, repo.sourceSync(source.key)!!.cursor)
                assertFalse(repo.sourceSync(source.key)!!.error!!.contains("SYNTHETIC"))
            }
        }
    }
    @Test fun boundedImportPausesAtLimitThenExplicitFullResumeContinues() = runBlocking {
        Repositories(path()).use { repo ->
            val source = forum(repo)
            var seed = repo.beginSourceSync(source.key)
            (2L..1000L).chunked(100).forEach { ids ->
                seed = repo.saveSourcePage(seed.copy(cursor = ids.min()), ids.map { message(source, it) })
            }
            SourceArchiveService(repo, { _, _ -> SourceMessagePage(listOf(message(source, 1)), null) }, { true }, pageDelayMs = 0, requestIntervalMs = 0).use { sync ->
                // Seed contains 999 rows and a cursor at the oldest imported message.
                sync.start(source.key)
                withTimeout(5000) { while (repo.sourceSync(source.key)!!.status == SyncStatus.RUNNING) delay(10) }
                assertEquals(SyncStatus.PAUSED, repo.sourceSync(source.key)!!.status)
                assertEquals(PauseReason.LIMIT, repo.sourceSync(source.key)!!.pauseReason)
                assertEquals(1000, repo.sourceSync(source.key)!!.messageCount)
            }
            SourceArchiveService(repo, { _, cursor ->
                assertEquals(1L, cursor)
                SourceMessagePage(emptyList(), null)
            }, { true }, pageDelayMs = 0, requestIntervalMs = 0).use { sync ->
                sync.start(source.key, null)
                withTimeout(5000) { while (repo.sourceSync(source.key)!!.status == SyncStatus.RUNNING) delay(10) }
                assertEquals(SyncStatus.COMPLETED, repo.sourceSync(source.key)!!.status)
                assertEquals(1000, repo.sourceArchive(source.key).total)
            }
        }
    }
    @Test fun floodWaitIsDurableAndDoesNotAdvanceCursor() = runBlocking {
        Repositories(path()).use { repo ->
            val source = forum(repo)
            SourceArchiveService(repo, { _, _ -> throw TelegramRequestException(429, "FLOOD_WAIT_30") }, { true }, requestIntervalMs = 0).use { sync ->
                sync.start(source.key)
                withTimeout(5000) { while (repo.sourceSync(source.key)!!.retryCount == 0) delay(10) }
                val state = repo.sourceSync(source.key)!!
                assertEquals(0L, state.cursor); assertTrue(state.nextRetryAt > System.currentTimeMillis() + 20_000)
                sync.stop(source.key)
                assertEquals(state.nextRetryAt, repo.sourceSync(source.key)!!.nextRetryAt)
            }
        }
    }
}
