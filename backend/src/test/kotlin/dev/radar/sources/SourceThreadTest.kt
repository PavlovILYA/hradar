package dev.radar.sources

import dev.radar.research.*
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

class SourceThreadTest {
    private class Native {
        val calls = mutableListOf<TdApi.Function<*>>()
        var canGet = true
        var targetChat = -200L
        var threadId = 9000L
        var anchorAvailable = true
        var targetAvailable = true
        var foreignMessage = false
        var deleted = false
        var forum = false
        var wrongAnchor = false
        var threadError: TelegramRequestException? = null
        var threadMap: Map<Long, Long> = emptyMap()
        fun message(chat: Long = -200, id: Long = 10000, thread: Long? = threadId) = TdApi.Message().apply {
            chatId = chat; this.id = id; date = 1700000000
            topicId = thread?.let { TdApi.MessageTopicThread(it) }
            content = TdApi.MessageText().apply { text = TdApi.FormattedText("Synthetic Java discussion", emptyArray()) }
        }
        suspend fun execute(fn: TdApi.Function<*>): TdApi.Object {
            calls += fn
            return when (fn) {
                is TdApi.GetChat -> TdApi.Chat().apply { id = fn.chatId; title = if (id == -100L) "Synthetic channel" else "Synthetic discussion group"; type = TdApi.ChatTypeSupergroup(-id, id == -100L) }
                is TdApi.GetSupergroup -> TdApi.Supergroup().apply { id = fn.supergroupId; isChannel = id == 100L; isForum = forum && id == 200L; status = if (id == 200L && !targetAvailable) TdApi.ChatMemberStatusBanned(0) else TdApi.ChatMemberStatusMember() }
                is TdApi.GetMessage -> { if (!anchorAvailable && fn.chatId == -100L || deleted && fn.chatId == -200L) throw TelegramRequestException(404, "MESSAGE_NOT_FOUND"); message(fn.chatId, if (wrongAnchor) fn.messageId + 1 else fn.messageId, if (fn.chatId == -100L) null else threadId) }
                is TdApi.GetMessageProperties -> TdApi.MessageProperties().apply { canGetMessageThread = canGet }
                is TdApi.GetMessageThread -> { threadError?.let { throw it }; TdApi.MessageThreadInfo().apply { chatId = targetChat; messageThreadId = threadMap[fn.messageId] ?: threadId; messages = arrayOf(message(targetChat, messageThreadId, messageThreadId).apply { if (forum) topicId = TdApi.MessageTopicForum(11) }) } }
                is TdApi.GetMessageThreadHistory -> TdApi.Messages().apply { messages = if (fn.fromMessageId == 0L) arrayOf(message(thread = (threadMap[fn.messageId] ?: threadId) + if (foreignMessage) 1 else 0)) else emptyArray() }
                is TdApi.SearchChatMessages -> TdApi.FoundChatMessages().apply {
                    val selected = (fn.topicId as? TdApi.MessageTopicThread)?.messageThreadId
                    messages = if (selected == null && threadMap.isNotEmpty()) threadMap.values.distinct().map { message(id = 10000 + it - 9000, thread = it) }.toTypedArray()
                    else arrayOf(message(id = 10000 + (selected ?: threadId) - 9000, thread = selected ?: threadId))
                    nextFromMessageId = 0
                }
                is TdApi.GetMessageLink -> throw TelegramRequestException(400, "NO_LINK")
                else -> error("Unexpected request ${fn.javaClass.simpleName}")
            }
        }
    }
    private fun repo() = Repositories(Files.createTempDirectory("source-thread").resolve("db"))
    private fun parent(repo: Repositories) = repo.saveSource(TelegramSource(chatId = -100, type = SourceType.CHANNEL, title = "Synthetic channel"))

    @Test fun explicitDiscussionUsesNativeLinkedGroupAndKeepsOriginalAnchorSeparate(): Unit = runBlocking {
        repo().use { repo ->
            val parent = parent(repo); val native = Native(); val reader = SourceReadService(repo, native::execute)
            val selected = reader.discussion(parent.key, 55)
            assertEquals(-200L, selected.chatId); assertEquals(SourceScope(ScopeKind.MESSAGE_THREAD, messageThreadId = 9000), selected.scope)
            assertEquals(-100L, selected.threadAnchorChatId); assertEquals(55L, selected.threadAnchorMessageId)
            assertEquals(selected.key, reader.discussion(parent.key, 55).key)
            assertEquals(2, repo.sources().size); assertTrue(repo.channels().isEmpty()); assertTrue(repo.mcpSources().isEmpty())
            val page = reader.recent(selected.key)
            assertEquals(9000L, page.messages.single().messageThreadId); assertEquals(-200L, page.messages.single().channelId)
            native.calls.filterIsInstance<TdApi.GetMessageThreadHistory>().forEach { assertEquals(-100L, it.chatId); assertEquals(55L, it.messageId); assertNotEquals(9000L, it.messageId) }
            assertTrue(native.calls.none { it is TdApi.SearchPublicChat || it is TdApi.GetChatHistory })
        }
    }
    @Test fun threadSearchUsesTypedThreadAndScopeIdWithoutForumConversion(): Unit = runBlocking {
        repo().use { repo ->
            val native = Native(); val reader = SourceReadService(repo, native::execute); val source = reader.discussion(parent(repo).key, 55)
            val page = reader.search(SearchRequest(mode = "sources", query = "Java", sourceKeys = listOf(source.key), withLinks = false))
            assertEquals(1, page.messages.size)
            val query = native.calls.filterIsInstance<TdApi.SearchChatMessages>().single()
            assertEquals(-200L, query.chatId); assertEquals(9000L, (query.topicId as TdApi.MessageTopicThread).messageThreadId)
            assertNull(page.messages.single().forumTopicId)
        }
    }
    @Test fun twoThreadsAndWholeGroupSearchStayScopedAndDeduplicateOverlap(): Unit = runBlocking {
        repo().use { repo ->
            val native = Native().apply { threadMap = mapOf(55L to 9000L, 56L to 9001L) }
            val reader = SourceReadService(repo, native::execute); val parent = parent(repo)
            val one = reader.discussion(parent.key, 55); val two = reader.discussion(parent.key, 56)
            val whole = repo.saveSource(TelegramSource(chatId = -200, type = SourceType.SUPERGROUP, title = "Synthetic whole"))
            val page = reader.search(SearchRequest(mode = "sources", query = "Java", sourceKeys = listOf(one.key, two.key, whole.key), limit = 6, withLinks = false))
            assertEquals(setOf(9000L, 9001L), page.messages.map { it.messageThreadId }.toSet())
            assertEquals(2, page.messages.size)
            assertEquals(1, reader.search(SearchRequest(mode = "sources", query = "Java", sourceKeys = listOf(two.key), withLinks = false)).messages.size)
        }
    }
    @Test fun manualThreadIdsCannotBeSavedAndUnavailablePropertiesNeverResolveDiscussion(): Unit = runBlocking {
        repo().use { repo ->
            val native = Native(); val parent = parent(repo); val reader = SourceReadService(repo, native::execute)
            assertFailsWith<IllegalArgumentException> { SourceCatalogService(TdlibSourceCatalog(native::execute), repo).save(SaveSourceInput(-200, SourceScope(ScopeKind.MESSAGE_THREAD, messageThreadId = 9000))) }
            native.canGet = false
            assertFailsWith<IllegalArgumentException> { reader.discussion(parent.key, 55) }
            assertTrue(native.calls.none { it is TdApi.GetMessageThread }); assertEquals(1, repo.sources().size)
            assertFalse(reader.detail(parent.key, 55).canGetMessageThread ?: true)
        }
    }
    @Test fun laterAnchorLossTargetAccessLossAndChangedMappingFailWithoutFallback(): Unit = runBlocking {
        repo().use { repo ->
            val native = Native(); val reader = SourceReadService(repo, native::execute); val source = reader.discussion(parent(repo).key, 55)
            native.anchorAvailable = false
            assertFailsWith<TelegramRequestException> { reader.recent(source.key) }
            native.anchorAvailable = true; native.targetAvailable = false
            assertFailsWith<TelegramRequestException> { reader.recent(source.key) }
            native.targetAvailable = true; native.threadId = 9999
            assertFailsWith<IllegalArgumentException> { reader.recent(source.key) }
            assertTrue(native.calls.none { it is TdApi.GetChatHistory })
        }
    }
    @Test fun foreignThreadMessagesRejectedAndDeletedDetailsExplicit(): Unit = runBlocking {
        repo().use { repo ->
            val native = Native(); val reader = SourceReadService(repo, native::execute); val source = reader.discussion(parent(repo).key, 55)
            native.foreignMessage = true
            assertFailsWith<IllegalArgumentException> { reader.recent(source.key) }
            native.foreignMessage = false; native.deleted = true
            val missing = reader.detail(source.key, 10000)
            assertEquals("unavailable", missing.availability); assertEquals(9000L, missing.messageThreadId)
        }
    }
    @Test fun forumTopicsCannotBecomeThreadsAndWrongAnchorsOrRateErrorsCannotSaveSource(): Unit = runBlocking {
        repo().use { repo ->
            val native = Native(); val parent = parent(repo); val reader = SourceReadService(repo, native::execute)
            native.forum = true
            assertFailsWith<IllegalArgumentException> { reader.discussion(parent.key, 55) }
            native.forum = false; native.wrongAnchor = true
            assertFailsWith<IllegalArgumentException> { reader.discussion(parent.key, 55) }
            native.wrongAnchor = false; native.threadError = TelegramRequestException(429, "FLOOD_WAIT_20")
            assertEquals(429, assertFailsWith<TelegramRequestException> { reader.discussion(parent.key, 55) }.code)
            assertEquals(1, repo.sources().size)
        }
    }
    @Test fun savedThreadRefreshRechecksOriginalAnchorAndPreservesItsIdentity(): Unit = runBlocking {
        repo().use { repo ->
            val native = Native(); val reader = SourceReadService(repo, native::execute)
            val source = reader.discussion(parent(repo).key, 55)
            val refreshed = SourceCatalogService(TdlibSourceCatalog(native::execute), repo).refresh(source.key)
            assertEquals(source.key, refreshed.key); assertEquals(source.scope, refreshed.scope)
            assertEquals(-100L, refreshed.threadAnchorChatId); assertEquals(55L, refreshed.threadAnchorMessageId)
        }
    }
    @Test fun observeOnlyReturnsSelectedThreadAndWholeChatOverlapRetainsIdentity(): Unit = runBlocking {
        repo().use { repo ->
            val native = Native(); val reader = SourceReadService(repo, native::execute); val source = reader.discussion(parent(repo).key, 55)
            val whole = repo.saveSource(TelegramSource(chatId = -200, type = SourceType.SUPERGROUP, title = "Synthetic whole"))
            assertNull(reader.observe(source.key, native.message(thread = 8000)))
            val scoped = reader.observe(source.key, native.message())!!; val all = reader.observe(whole.key, native.message())!!
            assertEquals(scoped.channelId to scoped.id, all.channelId to all.id)
            assertEquals(source.key, scoped.sourceKey); assertEquals(whole.key, all.sourceKey)
        }
    }
}
