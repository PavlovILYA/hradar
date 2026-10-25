package dev.radar.sources

import dev.radar.research.*
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

class SourceReadTest {
    private fun message(chat: Long, id: Long, topic: Int? = null, text: String = "Synthetic Java candidate") = TdApi.Message().apply {
        chatId = chat; this.id = id; date = 1700000000 + id.toInt()
        topicId = topic?.let { TdApi.MessageTopicForum(it) }
        content = TdApi.MessageText().apply { this.text = TdApi.FormattedText(text, emptyArray()) }
    }
    private class Native {
        val calls = mutableListOf<TdApi.Function<*>>()
        var fail: TelegramRequestException? = null
        var history: suspend (Long, Long, Int?) -> Array<TdApi.Message> = { _, _, _ -> emptyArray() }
        var search: suspend (TdApi.SearchChatMessages) -> TdApi.FoundChatMessages = { TdApi.FoundChatMessages().apply { messages = emptyArray() } }
        var detail: TdApi.Message? = null
        var deletedUser = false
        suspend fun execute(fn: TdApi.Function<*>): TdApi.Object {
            calls += fn
            when (fn) {
                is TdApi.GetChat -> return TdApi.Chat().apply { id = fn.chatId; title = "Synthetic source"; type = when (id) {
                    1L -> TdApi.ChatTypePrivate(1); 2L -> TdApi.ChatTypeBasicGroup(2); 9L -> TdApi.ChatTypeSecret(); else -> TdApi.ChatTypeSupergroup(id, id == 5L || id == 6L)
                } }
                is TdApi.GetUser -> return TdApi.User().apply { type = if (deletedUser) TdApi.UserTypeDeleted() else TdApi.UserTypeRegular() }
                is TdApi.GetBasicGroup -> return TdApi.BasicGroup().apply { isActive = true; status = TdApi.ChatMemberStatusMember() }
                is TdApi.GetSupergroup -> return TdApi.Supergroup().apply { id = fn.supergroupId; isChannel = id == 5L || id == 6L; isForum = id == 7L; status = TdApi.ChatMemberStatusMember(); if (id == 3L || id == 5L) usernames = TdApi.Usernames().apply { activeUsernames = arrayOf("synthetic_public") } }
                is TdApi.GetForumTopic -> return TdApi.ForumTopic().apply { info = TdApi.ForumTopicInfo().apply { chatId = fn.chatId; forumTopicId = fn.forumTopicId; name = "Synthetic topic ${fn.forumTopicId}" } }
            }
            fail?.let { throw it }
            return when (fn) {
                is TdApi.GetChatHistory -> TdApi.Messages().apply { messages = history(fn.chatId, fn.fromMessageId, null) }
                is TdApi.GetForumTopicHistory -> TdApi.Messages().apply { messages = history(fn.chatId, fn.fromMessageId, fn.forumTopicId) }
                is TdApi.SearchChatMessages -> search(fn)
                is TdApi.GetMessage -> detail ?: throw TelegramRequestException(400, "MESSAGE_NOT_FOUND")
                is TdApi.GetMessageLink -> throw TelegramRequestException(400, "MESSAGE_LINK_UNAVAILABLE")
                else -> error("Unexpected RPC ${fn.javaClass.simpleName}")
            }
        }
    }
    private fun save(repo: Repositories, id: Long, topic: Int? = null) = repo.saveSource(TelegramSource(chatId = id,
        type = when(id) { 1L -> SourceType.PRIVATE; 2L -> SourceType.BASIC_GROUP; 5L, 6L -> SourceType.CHANNEL; else -> SourceType.SUPERGROUP }, title = "Synthetic",
        isForum = id == 7L, scope = topic?.let { SourceScope(ScopeKind.FORUM_TOPIC, it) } ?: SourceScope()))

    @Test fun readsAllCloudChatKindsBySavedChatIdWithoutUsernameAndKeepsCaptions(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("local-read").resolve("db")).use { repo ->
            val native = Native(); val service = SourceReadService(repo, native::execute)
            native.history = { chat, cursor, _ -> if (cursor == 0L) arrayOf(message(chat, 3).apply { content = TdApi.MessagePhoto().apply { caption = TdApi.FormattedText("Synthetic caption", emptyArray()) } }) else emptyArray() }
            for (id in 1L..7L) {
                val source = save(repo, id)
                val page = service.recent(source.key)
                assertEquals("Synthetic caption", page.messages.single().text)
                assertEquals(id, page.messages.single().channelId); assertEquals(source.key, page.messages.single().sourceKey)
            }
            assertTrue(native.calls.none { it is TdApi.SearchPublicChat })
            assertTrue(repo.channels().isEmpty()); assertTrue(repo.historySyncs().isEmpty()); assertTrue(repo.mcpSources().isEmpty())
        }
    }
    @Test fun topicUsesNativeForumIdsAndWholeForumIncludesBothTopics(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("local-topic").resolve("db")).use { repo ->
            val native = Native(); val service = SourceReadService(repo, native::execute)
            native.history = { chat, cursor, topic -> if (cursor == 0L) (listOf(message(chat, 3, 11), message(chat, 2, 22)).filter { topic == null || (it.topicId as TdApi.MessageTopicForum).forumTopicId == topic }.toTypedArray()) else emptyArray() }
            val all = save(repo, 7); val one = save(repo, 7, 11); val two = save(repo, 7, 22)
            assertEquals(listOf(3L, 2L), service.recent(all.key).messages.map { it.id })
            assertEquals(listOf(3L), service.recent(one.key).messages.map { it.id })
            assertEquals(listOf(2L), service.recent(two.key).messages.map { it.id })
            assertEquals(setOf(11, 22), native.calls.filterIsInstance<TdApi.GetForumTopicHistory>().map { it.forumTopicId }.toSet())
        }
    }
    @Test fun shortInclusiveHistoryPagesDoNotLoseMessagesOrStopEarly(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("local-page").resolve("db")).use { repo ->
            val source = save(repo, 1); val native = Native(); val service = SourceReadService(repo, native::execute)
            native.history = { chat, cursor, _ -> when(cursor) { 0L -> arrayOf(message(chat, 5), message(chat, 4)); 4L -> arrayOf(message(chat, 4), message(chat, 3)); 3L -> arrayOf(message(chat, 3), message(chat, 2)); else -> emptyArray() } }
            val first = service.recent(source.key, limit = 3)
            assertEquals(listOf(5L, 4L, 3L), first.messages.map { it.id }); assertEquals(3L, first.nextCursor)
            assertEquals(listOf(2L), service.recent(source.key, first.nextCursor!!).messages.map { it.id })
        }
    }
    @Test fun selectedSearchDeduplicatesChatTopicOverlapAcrossPagesAndDoesNotResolveUsername(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("local-search").resolve("db")).use { repo ->
            val whole = save(repo, 7); val one = save(repo, 7, 11); val two = save(repo, 7, 22)
            val native = Native(); val service = SourceReadService(repo, native::execute)
            native.search = { q -> TdApi.FoundChatMessages().apply {
                val topic = (q.topicId as? TdApi.MessageTopicForum)?.forumTopicId
                messages = if (q.fromMessageId == 0L) arrayOf(message(q.chatId, if (topic == 22) 9 else 10, topic ?: 11)) else emptyArray()
                nextFromMessageId = if (q.fromMessageId == 0L) 8 else 0
            } }
            val input = SearchRequest(mode = "sources", query = "Java", sourceKeys = listOf(whole.key, one.key, two.key), limit = 6, withLinks = false)
            val first = service.search(input)
            assertEquals(setOf(10L, 9L), first.messages.map { it.id }.toSet()); assertEquals(2, first.messages.size)
            val second = service.search(input.copy(cursor = first.cursor))
            assertTrue(second.messages.isEmpty()); assertTrue(second.cursor.isEmpty())
            assertEquals(setOf(11, 22), native.calls.filterIsInstance<TdApi.SearchChatMessages>().mapNotNull { (it.topicId as? TdApi.MessageTopicForum)?.forumTopicId }.toSet())
            assertTrue(native.calls.none { it is TdApi.SearchPublicChat })
        }
    }
    @Test fun searchKeepsCompletedSourceOnPartialRateLimitAndRetriesFailedSource(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("local-rate").resolve("db")).use { repo ->
            val a = save(repo, 1); val b = save(repo, 2); val native = Native(); val service = SourceReadService(repo, native::execute)
            var limited = true
            native.search = { q -> if (q.chatId == 2L && limited) throw TelegramRequestException(429, "FLOOD_WAIT_20") else TdApi.FoundChatMessages().apply { messages = arrayOf(message(q.chatId, 1)); nextFromMessageId = 0 } }
            val input = SearchRequest(mode = "sources", query = "Java", sourceKeys = listOf(a.key, b.key), withLinks = false)
            val first = service.search(input); assertEquals("rate_limited", first.state); assertEquals(1, first.messages.size)
            limited = false
            val next = service.search(input.copy(cursor = first.cursor)); assertEquals(listOf(2L), next.messages.map { it.channelId }); assertTrue(next.cursor.isEmpty())
        }
    }
    @Test fun detailReflectsEditsAndMissingMessageWithoutInventingPrivateLinks(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("local-detail").resolve("db")).use { repo ->
            val source = save(repo, 1); val native = Native(); val service = SourceReadService(repo, native::execute)
            native.detail = message(1, 10, text = "Synthetic original")
            assertEquals("Synthetic original", service.detail(source.key, 10).text)
            native.detail = message(1, 10, text = "Synthetic edited")
            val edited = service.detail(source.key, 10); assertEquals("Synthetic edited", edited.text); assertNull(edited.link)
            native.detail = null
            val missing = service.detail(source.key, 10); assertEquals("unavailable", missing.availability); assertNull(missing.text)
        }
    }
    @Test fun deletedCloudUserRetainsReadableHistoryWhenNativeRpcAllowsIt(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("local-deleted-user").resolve("db")).use { repo ->
            val source = save(repo, 1); val native = Native().apply { deletedUser = true }
            native.history = { chat, cursor, _ -> if (cursor == 0L) arrayOf(message(chat, 10)) else emptyArray() }
            assertEquals(listOf(10L), SourceReadService(repo, native::execute).recent(source.key).messages.map { it.id })
        }
    }
    @Test fun rejectsWrongTopicAndSecretChatInsteadOfFallingBackToWholeChat(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("local-scope-denied").resolve("db")).use { repo ->
            val source = save(repo, 7, 11); val secret = save(repo, 9); val native = Native()
            native.history = { chat, _, _ -> arrayOf(message(chat, 10, 22)) }
            val service = SourceReadService(repo, native::execute)
            assertFailsWith<IllegalArgumentException> { service.recent(source.key) }
            assertEquals(403, assertFailsWith<TelegramRequestException> { service.recent(secret.key) }.code)
            assertTrue(native.calls.none { it is TdApi.GetChatHistory && it.chatId == 9L })
        }
    }
    @Test fun sourceRemovalDuringSuspendedReadCannotReturnRevokedContent(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("local-revoked").resolve("db")).use { repo ->
            val source = save(repo, 1); val native = Native(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            native.history = { chat, cursor, _ -> if (cursor == 0L) { entered.complete(Unit); release.await(); arrayOf(message(chat, 10)) } else emptyArray() }
            val pending = async { runCatching { SourceReadService(repo, native::execute).recent(source.key) } }
            entered.await(); repo.deleteSource(source.key); release.complete(Unit)
            assertTrue(pending.await().exceptionOrNull() is ResourceNotFound)
        }
    }
    @Test fun nativeRepeatedCursorIsExplicitAndDoesNotClaimExhaustion(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("local-stalled").resolve("db")).use { repo ->
            val source = save(repo, 1); val native = Native()
            native.history = { chat, _, _ -> arrayOf(message(chat, 10)) }
            val page = SourceReadService(repo, native::execute).recent(source.key, before = 10)
            assertEquals("cursor_stalled", page.state); assertEquals(10L, page.nextCursor); assertTrue(page.messages.isEmpty())
        }
    }
    @Test fun sharedSearchDispatchKeepsCrossChatIdentityAndTemplateAndOrNot(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("local-template").resolve("db")).use { repo ->
            val a = save(repo, 1); val b = save(repo, 2); val native = Native()
            val template = repo.saveTemplate(SearchTemplate(name = "Synthetic", telegramQuery = "Java", required = listOf("Java"), any = listOf("Spring", "Ktor"), excluded = listOf("junior")))
            native.search = { q -> TdApi.FoundChatMessages().apply { messages = if (q.fromMessageId == 0L) arrayOf(
                message(q.chatId, 10, text = "Java Spring synthetic"), message(q.chatId, 9, text = "Java Ktor junior synthetic"),
                message(q.chatId, 8, text = "Java only synthetic"), message(q.chatId, 7, text = "Spring only synthetic")) else arrayOf(message(q.chatId, 10, text = "Java Spring synthetic"))
                nextFromMessageId = if (q.fromMessageId == 0L) 6 else 0
            } }
            val service = SearchService(dev.radar.telegram.TdlibGateway(1, "unused", Files.createTempDirectory("unused-gateway")), repo, MatchingService(), native::execute)
            val request = SearchRequest(mode = "sources", sourceKeys = listOf(a.key, b.key), query = "ignored", templateId = template.id, limit = 10, withLinks = false)
            val first = service.search(request)
            assertEquals(setOf(1L to 10L, 2L to 10L), first.messages.map { it.channelId to it.id }.toSet()); assertEquals(2, first.messages.size)
            assertTrue(first.messages.all { it.explanation?.matches == true })
            assertTrue(service.search(request.copy(cursor = first.cursor)).messages.isEmpty())
            assertFailsWith<IllegalArgumentException> { service.search(request.copy(sourceKeys = listOf(a.key), cursor = first.cursor)) }
        }
    }
    @Test fun nativeExclusiveHistoryReachesRealEmptyBottomForChatAndTopic(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("native-exclusive-bottom").resolve("db")).use { repo ->
            val native = Native(); val service = SourceReadService(repo, native::execute)
            native.history = { chat, cursor, topic -> (5L downTo 1L).filter { cursor == 0L || it < cursor }.take(2).map { message(chat, it, topic) }.toTypedArray() }
            for (source in listOf(save(repo, 1), save(repo, 7, 11))) {
                val first = service.recent(source.key, limit = 3)
                assertEquals(listOf(5L, 4L, 3L), first.messages.map { it.id }); assertEquals(3L, first.nextCursor)
                val last = service.recent(source.key, before = first.nextCursor!!, limit = 3)
                assertEquals(listOf(2L, 1L), last.messages.map { it.id }); assertNull(last.nextCursor); assertEquals("ok", last.state)
            }
        }
    }
    @Test fun searchNeverAdvancesInvalidNativeCursorAndCapsInspectedIdentitiesPrecisely(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("native-search-bound").resolve("db")).use { repo ->
            val source = save(repo, 1); val native = Native(); val service = SourceReadService(repo, native::execute)
            var call = 0; var remaining = 20_000L
            native.search = { q ->
                val count = if (call++ == 0) 99 else q.limit
                TdApi.FoundChatMessages().apply { messages = (remaining downTo remaining - count + 1).map { message(1, it) }.toTypedArray(); remaining -= count; nextFromMessageId = remaining }
            }
            val input = SearchRequest(mode = "sources", sourceKeys = listOf(source.key), query = "Java", limit = 100, withLinks = false)
            var cursor = ""; var total = 0
            do { val page = service.search(input.copy(cursor = cursor)); total += page.messages.size; cursor = page.cursor } while (cursor.isNotEmpty())
            assertEquals(10_000, total)
            assertEquals(1, native.calls.filterIsInstance<TdApi.SearchChatMessages>().last().limit)
            native.search = { q -> TdApi.FoundChatMessages().apply { messages = arrayOf(message(1, 10)); nextFromMessageId = if (q.fromMessageId == 0L) 9 else 11 } }
            val first = service.search(input)
            val invalid = service.search(input.copy(cursor = first.cursor))
            assertEquals("partial", invalid.state)
            service.search(input.copy(cursor = invalid.cursor))
            assertEquals(listOf(9L, 9L), native.calls.filterIsInstance<TdApi.SearchChatMessages>().takeLast(2).map { it.fromMessageId })
        }
    }

    @Test fun unsupportedAndExpiredContentHaveExplicitAvailabilityAndPaidMediaCaptionIsReadable(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("local-unavailable").resolve("db")).use { repo ->
            val source = save(repo, 1); val native = Native(); val service = SourceReadService(repo, native::execute)
            native.detail = message(1, 10).apply { content = TdApi.MessageUnsupported() }
            assertEquals("unsupported", service.detail(source.key, 10).availability)
            native.detail = message(1, 10).apply { content = TdApi.MessageExpiredPhoto() }
            assertEquals("unavailable", service.detail(source.key, 10).availability)
            native.detail = message(1, 10).apply { content = TdApi.MessagePaidMedia().apply { caption = TdApi.FormattedText("Synthetic paid caption", emptyArray()) } }
            assertEquals("Synthetic paid caption", service.detail(source.key, 10).text)
        }
    }

    @Test fun accessErrorsAndRateLimitsPreserveNativeCodesAndCancellationPropagates(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("local-errors").resolve("db")).use { repo ->
            val source = save(repo, 1); val native = Native(); val service = SourceReadService(repo, native::execute)
            for (code in listOf(403, 429, 500)) {
                native.fail = TelegramRequestException(code, "Synthetic sensitive native detail")
                assertEquals(code, assertFailsWith<TelegramRequestException> { service.recent(source.key) }.code)
            }
            native.fail = null
            val entered = CompletableDeferred<Unit>(); native.history = { _, _, _ -> entered.complete(Unit); awaitCancellation() }
            val pending = async { service.recent(source.key) }; entered.await(); pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
    }
}
