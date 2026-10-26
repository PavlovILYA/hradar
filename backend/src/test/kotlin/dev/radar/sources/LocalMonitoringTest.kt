package dev.radar.sources

import dev.radar.research.*
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

class LocalMonitoringTest {
    private fun repo() = Repositories(Files.createTempDirectory("local-monitor-").resolve("db.sqlite"))
    private fun save(repo: Repositories, topic: Int? = null) = repo.saveSource(TelegramSource(chatId = 123,
        type = if (topic == null) SourceType.PRIVATE else SourceType.SUPERGROUP,
        title = "Synthetic local", isForum = topic != null,
        scope = topic?.let { SourceScope(ScopeKind.FORUM_TOPIC, it) } ?: SourceScope()))
    private fun rule(repo: Repositories, name: String = "Synthetic Java") = repo.saveTemplate(SearchTemplate(name = name,
        required = listOf("Java"), any = listOf("Kotlin", "Ktor"), excluded = listOf("spam"), telegramQuery = "Java"))
    private fun message(source: TelegramSource, id: Long, text: String = "Synthetic Java Kotlin candidate") = ResearchMessage(
        id, source.chatId, 2000000000 + id.toInt(), text, source.title, null, sourceKey = source.key, forumTopicId = source.scope.forumTopicId)
    private fun native(id: Long) = TdApi.Message().apply { chatId = 123; this.id = id; date = 2000000000 + id.toInt() }
    private fun monitor(repo: Repositories, recent: suspend (String, Long, Int) -> SourceMessagePage,
        observe: suspend (String, TdApi.Message) -> ResearchMessage? = { key, native -> message(repo.sources().first { it.key == key }, native.id) }) =
        LocalMonitoringService(repo, SourceReadService(repo) { error("Unexpected native RPC") }, { true },
            requestIntervalMs = 0, readRecent = recent, observe = observe, now = { 2000000000000L })

    @Test fun initialEnableSnapshotsHeadAndNeverNotifiesHistoricalMessages(): Unit = runBlocking {
        repo().use { repo ->
            val source = save(repo); val template = rule(repo)
            val monitor = monitor(repo, { _, cursor, limit ->
                assertEquals(0L, cursor)
                SourceMessagePage(listOf(message(source, 50)).take(limit), null)
            })
            monitor.use {
                val state = it.configure(source.key, template.id, true)
                assertEquals(50L, state.checkpoint); assertEquals(2000000000, state.sinceDate)
                assertTrue(repo.localMatches().items.isEmpty())
                it.processUpdate(native(49)); assertTrue(repo.localMatches().items.isEmpty())
                it.processUpdate(native(51)); assertEquals(listOf(51L), repo.localMatches().items.map { m -> m.message.id })
                assertEquals(50L, it.list().single().checkpoint)
            }
        }
    }
    @Test fun pausedBacklogAndRestartRecoverWithoutDuplicatesOrExternalEffects(): Unit = runBlocking {
        val path = Files.createTempDirectory("local-monitor-restart-").resolve("db")
        var key = ""; var templateId = ""
        Repositories(path).use { repo ->
            val source = save(repo); key = source.key; templateId = rule(repo).id
            monitor(repo, { _, _, _ -> SourceMessagePage(listOf(message(source, 5)), 5) }).use { it.configure(key, templateId, true); it.configure(key, templateId, false) }
        }
        Repositories(path).use { repo ->
            val source = repo.sources().single()
            monitor(repo, { _, cursor, _ -> when (cursor) {
                0L -> SourceMessagePage(listOf(message(source, 8), message(source, 7)), 7)
                7L -> SourceMessagePage(listOf(message(source, 7), message(source, 6)), 6)
                else -> SourceMessagePage(listOf(message(source, 5)), null)
            } }).use {
                assertFalse(it.list().single().enabled)
                it.configure(key, templateId, true); it.pollOnce(); it.pollOnce()
                assertEquals(setOf(6L, 7L, 8L), repo.localMatches().items.map { m -> m.message.id }.toSet())
                assertEquals(3, repo.localMatches().items.size); assertEquals(8L, it.list().single().checkpoint)
                assertTrue(repo.notifications().isEmpty()); assertTrue(repo.channels().isEmpty())
                assertEquals(0, repo.historyMessages(source.chatId, 10, null, "").total)
                assertTrue(repo.mcpSources().isEmpty()); assertNull(repo.recruitmentPublication(source.chatId, 8))
            }
        }
    }
    @Test fun explicitPairsDoNotInheritLegacyAllAndSameRuleWholeTopicDeduplicates(): Unit = runBlocking {
        repo().use { repo ->
            val whole = repo.saveSource(TelegramSource(chatId = 123, type = SourceType.SUPERGROUP, title = "Synthetic", isForum = true))
            val topic = save(repo, 11); val template = rule(repo)
            repo.saveTemplate(template.copy(subscribed = true, sourceMode = "all"))
            repo.configureBot(1, setOf(7)); repo.registerBotRecipient(1, 7, 7, 2000000000000)
            assertTrue(repo.localSubscriptions().isEmpty())
            monitor(repo, { _, _, _ -> SourceMessagePage(emptyList(), null) }, { key, update -> message(repo.sources().first { it.key == key }, update.id).copy(forumTopicId = 11) }).use {
                it.configure(whole.key, template.id, true); it.configure(topic.key, template.id, true)
                it.processUpdate(native(1)); it.processUpdate(native(1))
                assertEquals(1, repo.localMatches().items.size)
                assertTrue(repo.notifications().isEmpty()); assertTrue(repo.templates().single().subscribed)
                val other = rule(repo, "Other synthetic")
                it.configure(topic.key, other.id, true); it.processUpdate(native(1))
                assertEquals(2, repo.localMatches().items.size)
                assertEquals(0, repo.botDeliveryCounts(1).pending)
            }
        }
    }
    @Test fun matchingKeepsAndOrNotAndReadStateSurvivesReopen(): Unit = runBlocking {
        val path = Files.createTempDirectory("local-match-read-").resolve("db")
        Repositories(path).use { repo ->
            val source = save(repo); val template = rule(repo)
            monitor(repo, { _, _, _ -> SourceMessagePage(emptyList(), null) }, { _, update ->
                message(source, update.id, when(update.id) { 1L -> "Java"; 2L -> "Kotlin"; 3L -> "Java Kotlin spam"; else -> "Java Ktor" })
            }).use {
                it.configure(source.key, template.id, true)
                (1L..4L).forEach { id -> it.processUpdate(native(id)) }
            }
            val page = repo.localMatches(); assertEquals(listOf(4L), page.items.map { it.message.id }); assertEquals(1, page.unread)
            assertTrue(repo.markLocalRead(page.items.single().id)); assertEquals(0, repo.localMatches().unread)
        }
        Repositories(path).use { assertTrue(it.localMatches().items.single().read); assertEquals(0, it.localMatches().unread) }
    }
    @Test fun recoveryIsBoundedAndOutOfOrderLiveNeverMovesCheckpoint(): Unit = runBlocking {
        repo().use { repo ->
            val source = save(repo); val template = rule(repo); var calls = 0
            monitor(repo, { _, cursor, _ -> calls++; if (calls == 1) SourceMessagePage(listOf(message(source, 1)), 1)
                else SourceMessagePage(listOf(message(source, if (cursor == 0L) 100 else cursor - 1)), if (cursor == 0L) 100 else cursor - 1)
            }).use {
                it.configure(source.key, template.id, true)
                it.processUpdate(native(200)); it.processUpdate(native(150))
                assertEquals(1L, it.list().single().checkpoint)
                it.pollOnce(); assertEquals(5, calls); assertEquals(1L, it.list().single().checkpoint)
                assertEquals(97L, it.list().single().recoveryCursor)
                assertEquals(100L, it.list().single().recoveryHead)
            }
        }
    }
    @Test fun disablingOrChangingTemplateDuringRpcCannotWriteLateMatches(): Unit = runBlocking {
        repo().use { repo ->
            val source = save(repo); val template = rule(repo); var page = 0
            lateinit var service: LocalMonitoringService
            service = monitor(repo, { _, _, _ ->
                if (page++ == 0) SourceMessagePage(listOf(message(source, 1)), 1)
                else { service.configure(source.key, template.id, false); SourceMessagePage(listOf(message(source, 2)), null) }
            })
            service.use { it.configure(source.key, template.id, true); it.pollOnce(); assertTrue(repo.localMatches().items.isEmpty()); assertFalse(it.list().single().enabled) }
            monitor(repo, { _, _, _ -> repo.saveTemplate(template.copy(excluded = listOf("candidate"))); SourceMessagePage(listOf(message(source, 3)), null) }).use {
                it.configure(source.key, template.id, true); it.pollOnce(); assertTrue(repo.localMatches().items.isEmpty())
            }
        }
    }
    @Test fun deletionDuringRecoveryDropsPairWithoutRecreation(): Unit = runBlocking {
        repo().use { repo ->
            val source = save(repo); val template = rule(repo); var page = 0
            monitor(repo, { _, _, _ -> if (page++ == 0) SourceMessagePage(listOf(message(source, 1)), 1)
                else { repo.deleteSource(source.key); SourceMessagePage(listOf(message(source, 2)), null) }
            }).use { it.configure(source.key, template.id, true); it.pollOnce(); assertTrue(repo.localMatches().items.isEmpty()); assertTrue(it.list().isEmpty()) }
        }
    }
    @Test fun firstActivationFloodPersistsAcrossReopenWithoutCreatingAnyPair(): Unit = runBlocking {
        val path = Files.createTempDirectory("local-activation-flood-").resolve("db")
        var key = ""; var templateId = ""
        Repositories(path).use { repo ->
            val source = save(repo); key = source.key; templateId = rule(repo).id
            monitor(repo, { _, _, _ -> throw TelegramRequestException(429, "FLOOD_WAIT_30") }).use {
                assertFailsWith<TelegramRequestException> { it.configure(key, templateId, true) }
                assertTrue(repo.localSubscriptions().isEmpty()); assertEquals(2000000030000, repo.localMonitoringRetryAt())
            }
        }
        Repositories(path).use { repo ->
            var calls = 0
            monitor(repo, { _, _, _ -> calls++; SourceMessagePage(emptyList(), null) }).use {
                assertFailsWith<IllegalStateException> { it.configure(key, templateId, true) }
                assertEquals(0, calls); assertTrue(repo.localSubscriptions().isEmpty())
            }
        }
    }
    @Test fun failedInitialSnapshotDoesNotCreatePairAndGlobalFloodBlocksAnotherActivation(): Unit = runBlocking {
        repo().use { repo ->
            val first = save(repo); val template = rule(repo)
            monitor(repo, { _, _, _ -> throw TelegramRequestException(403, "SYNTHETIC") }).use {
                assertFailsWith<TelegramRequestException> { it.configure(first.key, template.id, true) }
                assertTrue(repo.localSubscriptions().isEmpty())
            }
            var calls = 0
            monitor(repo, { _, _, _ -> if (calls++ == 0) SourceMessagePage(listOf(message(first, 1)), 1)
                else throw TelegramRequestException(429, "FLOOD_WAIT_30") }).use {
                it.configure(first.key, template.id, true); it.pollOnce()
                val other = repo.saveSource(TelegramSource(chatId = 456, type = SourceType.PRIVATE, title = "Other synthetic"))
                assertFailsWith<IllegalStateException> { it.configure(other.key, template.id, true) }
                assertEquals(2, calls); assertTrue(repo.localSubscriptions(other.key).isEmpty())
                assertFailsWith<ResourceNotFound> { it.list("missing-source") }
            }
        }
    }
    @Test fun floodAndDeniedAccessPersistSafeErrorCursorAndDeadline(): Unit = runBlocking {
        repo().use { repo ->
            val source = save(repo); val template = rule(repo); var page = 0
            monitor(repo, { _, _, _ -> if (page++ == 0) SourceMessagePage(listOf(message(source, 1)), 1)
                else throw TelegramRequestException(429, "FLOOD_WAIT_30 synthetic sensitive")
            }).use {
                it.configure(source.key, template.id, true); it.pollOnce()
                val state = it.list().single(); assertEquals(1L, state.checkpoint); assertEquals(2000000030000, state.retryAt)
                assertEquals("rate_limited", state.status); assertFalse(state.error!!.contains("sensitive"))
                it.configure(source.key, template.id, false); assertEquals(state.retryAt, it.list().single().retryAt)
            }
        }
        repo().use { repo ->
            val source = save(repo); val template = rule(repo); var page = 0
            monitor(repo, { _, _, _ -> if (page++ == 0) SourceMessagePage(listOf(message(source, 1)), 1)
                else throw TelegramRequestException(403, "synthetic sensitive private")
            }).use { it.configure(source.key, template.id, true); it.pollOnce(); assertEquals("unavailable", it.list().single().status); assertFalse(it.list().single().error!!.contains("private")) }
        }
    }
    @Test fun retryConfigureWaitsForDeadlineThenRecoversOnlyRequestedPair(): Unit = runBlocking {
        repo().use { repo ->
            val source = save(repo); val template = rule(repo)
            var instant = 2000000000000L
            var attempts = 0
            val reads = mutableListOf<String>()
            val reader = SourceReadService(repo) { error("Unexpected native RPC") }
            LocalMonitoringService(repo, reader, { true }, requestIntervalMs = 0, now = { instant },
                readRecent = { key, _, _ ->
                    reads += key
                    if (key != source.key) SourceMessagePage(emptyList(), null)
                    else when (attempts++) {
                        0 -> SourceMessagePage(listOf(message(source, 1)), 1)
                        1 -> throw TelegramRequestException(403, "SYNTHETIC_DENIED")
                        else -> SourceMessagePage(listOf(message(source, 2)), null)
                    }
                }).use { service ->
                service.configure(source.key, template.id, true); service.pollOnce()
                val denied = service.list(source.key).single()
                assertEquals("unavailable", denied.status); assertNotNull(denied.error)
                val other = repo.saveSource(TelegramSource(chatId = 456, type = SourceType.PRIVATE, title = "Other synthetic"))
                service.configure(other.key, template.id, true)
                reads.clear()
                assertEquals(denied, service.configure(source.key, template.id, true))
                assertTrue(reads.isEmpty())
                instant = denied.retryAt
                val recovered = service.configure(source.key, template.id, true)
                assertEquals(listOf(source.key), reads)
                assertEquals("active", recovered.status); assertNull(recovered.error)
                assertEquals(2L, recovered.checkpoint); assertEquals(0L, recovered.retryAt)
                assertEquals(listOf(2L), repo.localMatches().items.map { it.message.id })
            }
        }
    }

    @Test fun cancellingRecoveryBeforeCommitPreservesCursorAndMatches(): Unit = runBlocking {
        repo().use { repo ->
            val source = save(repo); val template = rule(repo)
            val reading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var calls = 0
            monitor(repo, { _, _, _ ->
                if (calls++ == 0) SourceMessagePage(listOf(message(source, 1)), 1)
                else withContext(NonCancellable) {
                    reading.complete(Unit); release.await()
                    SourceMessagePage(listOf(message(source, 2)), null)
                }
            }).use { service ->
                service.configure(source.key, template.id, true)
                val before = service.list().single()
                val request = launch { service.pollOnce() }
                reading.await(); request.cancel(); release.complete(Unit); request.join()
                assertEquals(before, service.list().single())
                assertTrue(repo.localMatches().items.isEmpty())
            }
        }
    }

    @Test fun nativeObserveMonitoringCoversCloudGroupChannelForumTopicAndThreadSources(): Unit = runBlocking {
        val cases = listOf(
            Triple(SourceType.PRIVATE, false, SourceScope()),
            Triple(SourceType.BASIC_GROUP, false, SourceScope()),
            Triple(SourceType.SUPERGROUP, true, SourceScope()),
            Triple(SourceType.SUPERGROUP, false, SourceScope()),
            Triple(SourceType.CHANNEL, true, SourceScope()),
            Triple(SourceType.CHANNEL, false, SourceScope()),
            Triple(SourceType.SUPERGROUP, false, SourceScope()),
            Triple(SourceType.SUPERGROUP, false, SourceScope(ScopeKind.FORUM_TOPIC, 11)),
            Triple(SourceType.SUPERGROUP, false, SourceScope(ScopeKind.FORUM_TOPIC, 22)),
            Triple(SourceType.SUPERGROUP, false, SourceScope(ScopeKind.MESSAGE_THREAD, messageThreadId = 9000)),
        )
        for ((index, case) in cases.withIndex()) repo().use { repo ->
            val (sourceType, public, selectedScope) = case
            val chat = if (sourceType == SourceType.PRIVATE) 101L else -100L - index
            val forum = index in 6..8
            val selected = repo.saveSource(TelegramSource(chatId = chat, type = sourceType, title = "Synthetic matrix $index",
                username = if (public) "synthetic_public" else null, isPublic = public, isForum = forum, scope = selectedScope,
                threadAnchorChatId = if (selectedScope.kind == ScopeKind.MESSAGE_THREAD) -999 else null,
                threadAnchorMessageId = if (selectedScope.kind == ScopeKind.MESSAGE_THREAD) 55 else null))
            val template = rule(repo)
            val calls = mutableListOf<TdApi.Function<*>>()
            fun publication(id: Long = 10) = native(id).apply {
                chatId = chat
                topicId = when { selectedScope.kind == ScopeKind.MESSAGE_THREAD -> TdApi.MessageTopicThread(9000)
                    forum -> TdApi.MessageTopicForum(selectedScope.forumTopicId ?: 11)
                    else -> null }
                content = if (sourceType == SourceType.CHANNEL) TdApi.MessagePhoto().apply { caption = TdApi.FormattedText("Synthetic Java Ktor caption", emptyArray()) }
                    else TdApi.MessageText().apply { text = TdApi.FormattedText("Synthetic Java Kotlin candidate", emptyArray()) }
            }
            val reader = SourceReadService(repo) { fn ->
                calls += fn
                when (fn) {
                    is TdApi.GetChat -> TdApi.Chat().apply {
                        id = fn.chatId; title = "Synthetic native"
                        this.type = when {
                            id == -999L -> TdApi.ChatTypeSupergroup(999, true)
                            sourceType == SourceType.PRIVATE -> TdApi.ChatTypePrivate(101)
                            sourceType == SourceType.BASIC_GROUP -> TdApi.ChatTypeBasicGroup(102)
                            else -> TdApi.ChatTypeSupergroup(-chat, sourceType == SourceType.CHANNEL)
                        }
                    }
                    is TdApi.GetUser -> TdApi.User().apply { this.type = TdApi.UserTypeRegular() }
                    is TdApi.GetBasicGroup -> TdApi.BasicGroup().apply { isActive = true; status = TdApi.ChatMemberStatusMember() }
                    is TdApi.GetSupergroup -> TdApi.Supergroup().apply {
                        id = fn.supergroupId; isChannel = sourceType == SourceType.CHANNEL || id == 999L
                        isForum = forum; status = TdApi.ChatMemberStatusMember()
                        if (public) usernames = TdApi.Usernames().apply { activeUsernames = arrayOf("synthetic_public") }
                    }
                    is TdApi.GetForumTopic -> TdApi.ForumTopic().apply { info = TdApi.ForumTopicInfo().apply {
                        chatId = fn.chatId; forumTopicId = fn.forumTopicId; name = "Synthetic topic" } }
                    is TdApi.GetMessage -> publication(fn.messageId).apply { chatId = fn.chatId; topicId = null }
                    is TdApi.GetMessageProperties -> TdApi.MessageProperties().apply { canGetMessageThread = true }
                    is TdApi.GetMessageThread -> TdApi.MessageThreadInfo().apply { chatId = chat; messageThreadId = 9000; messages = arrayOf(publication(9000)) }
                    else -> error("Unexpected matrix native call ${fn.javaClass.simpleName}")
                }
            }
            LocalMonitoringService(repo, reader, { true }, requestIntervalMs = 0,
                readRecent = { _, _, _ -> SourceMessagePage(emptyList(), null) }, now = { 2000000000000L }).use { service ->
                service.configure(selected.key, template.id, true)
                service.processUpdate(publication()); service.processUpdate(publication())
                val match = repo.localMatches().items.single()
                assertEquals(selected.key, match.message.sourceKey, "case $index")
                assertEquals(chat, match.message.channelId, "case $index")
                assertEquals(10L, match.message.id, "case $index")
                assertEquals(selectedScope.forumTopicId ?: if (forum) 11 else null, match.message.forumTopicId)
                assertEquals(selectedScope.messageThreadId, match.message.messageThreadId)
                assertEquals(0L, service.list().single().checkpoint)
                assertTrue(calls.any { it is TdApi.GetChat }, "current access must be read in case $index")
                assertTrue(calls.none { it is TdApi.SearchPublicChat }, "saved identity must avoid username resolution")
                assertTrue(repo.notifications().isEmpty()); assertTrue(repo.channels().isEmpty()); assertTrue(repo.mcpSources().isEmpty())
                if (selectedScope.kind == ScopeKind.FORUM_TOPIC) {
                    service.processUpdate(publication(11).apply { topicId = TdApi.MessageTopicForum(if (selectedScope.forumTopicId == 11) 22 else 11) })
                    assertEquals(1, repo.localMatches().items.size, "wrong topic must be rejected")
                }
            }
        }
    }

}
