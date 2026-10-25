package dev.radar.sources

import dev.radar.research.*
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

class SourceCatalogTest {
    @Test fun nativeMetadataSupportsChatKindsAndDoesNotReturnMessageOrDraftBodies(): Unit = runBlocking {
        val requested = mutableListOf<TdApi.Function<*>>()
        val catalog = TdlibSourceCatalog { fn ->
            requested += fn
            when (fn) {
                is TdApi.GetChat -> TdApi.Chat().apply { id = fn.chatId; title = "Synthetic"; type = when (id) {
                    1L -> TdApi.ChatTypePrivate(1); 2L -> TdApi.ChatTypeBasicGroup(2); 9L -> TdApi.ChatTypeSecret(); else -> TdApi.ChatTypeSupergroup(id, id in listOf(5L, 6L))
                }; lastMessage = TdApi.Message().apply { content = TdApi.MessageText().apply { text = TdApi.FormattedText("Synthetic body must stay private", emptyArray()) } } }
                is TdApi.GetUser -> TdApi.User().apply { id = 1; type = TdApi.UserTypeRegular() }
                is TdApi.GetBasicGroup -> TdApi.BasicGroup().apply { isActive = true; status = TdApi.ChatMemberStatusMember() }
                is TdApi.GetSupergroup -> TdApi.Supergroup().apply { id = fn.supergroupId; isChannel = id in listOf(5L, 6L); isForum = id == 7L; status = if (id == 8L) TdApi.ChatMemberStatusBanned(0) else TdApi.ChatMemberStatusMember(); if (id in listOf(3L, 5L)) usernames = TdApi.Usernames().apply { activeUsernames = arrayOf("synthetic_name") } }
                else -> error("Unexpected body/join request")
            }
        }
        val values = (1L..8L).map { catalog.describe(it)!! }
        assertEquals(listOf(SourceType.PRIVATE, SourceType.BASIC_GROUP, SourceType.SUPERGROUP, SourceType.SUPERGROUP, SourceType.CHANNEL, SourceType.CHANNEL, SourceType.SUPERGROUP, SourceType.SUPERGROUP), values.map { it.type })
        assertEquals(listOf(false, false, true, false, true, false, false, false), values.map { it.isPublic })
        assertTrue(values[6].isForum); assertEquals(SourceAccess.UNAVAILABLE, values[7].access)
        assertNull(catalog.describe(9))
        val serialized = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(TelegramSource.serializer()), values)
        assertFalse(serialized.contains("Synthetic body")); assertFalse(serialized.contains("lastMessage"))
        assertTrue(requested.all { it is TdApi.GetChat || it is TdApi.GetUser || it is TdApi.GetBasicGroup || it is TdApi.GetSupergroup })
    }
    @Test fun nativeForumPaginationUsesReturnedOffsetsAndParentIdentity(): Unit = runBlocking {
        val requests = mutableListOf<TdApi.Function<*>>()
        val catalog = TdlibSourceCatalog { fn ->
            requests += fn
            when (fn) {
                is TdApi.GetChat -> TdApi.Chat().apply { id = -1001; title = "Synthetic forum"; type = TdApi.ChatTypeSupergroup(1, false) }
                is TdApi.GetSupergroup -> TdApi.Supergroup().apply { isForum = true; status = TdApi.ChatMemberStatusMember() }
                is TdApi.GetForumTopics -> TdApi.ForumTopics().apply { topics = arrayOf(TdApi.ForumTopic().apply { info = TdApi.ForumTopicInfo().apply { chatId = -1001; forumTopicId = 11; name = "Synthetic topic" } }); nextOffsetDate = 10; nextOffsetMessageId = 42; nextOffsetForumTopicId = 11 }
                is TdApi.GetForumTopic -> TdApi.ForumTopic().apply { info = TdApi.ForumTopicInfo().apply { chatId = -1002; forumTopicId = fn.forumTopicId; name = "Wrong parent" } }
                else -> error("Unexpected request")
            }
        }
        val page = catalog.topics(-1001, TopicCursor(5, 22, 4))
        val request = requests.filterIsInstance<TdApi.GetForumTopics>().single()
        assertEquals(listOf(5L, 22L, 4L), listOf(request.offsetDate.toLong(), request.offsetMessageId, request.offsetForumTopicId.toLong()))
        assertEquals(TopicCursor(10, 42, 11), page.next)
        assertEquals(SourceScope(ScopeKind.FORUM_TOPIC, 11), page.items.single().scope)
        assertFailsWith<IllegalArgumentException> { catalog.topic(-1001, 11) }
    }
    private class Fake : SourceCatalogPort {
        var fail = false
        override suspend fun chatIds(query: String) = (1L..70L).toList()
        override suspend fun describe(chatId: Long) = TelegramSource(chatId = chatId, title = "Synthetic $chatId", type = SourceType.PRIVATE, access = if (fail) SourceAccess.UNAVAILABLE else SourceAccess.AVAILABLE)
        override suspend fun topics(chatId: Long, cursor: TopicCursor) = TopicPage(emptyList())
        override suspend fun topic(chatId: Long, topicId: Int) = describe(chatId).copy(scope = SourceScope(ScopeKind.FORUM_TOPIC, topicId))
    }
    @Test fun boundedSnapshotPaginationExpiryFiltersAndExplicitSave(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("catalog-").resolve("db")).use { repo ->
            var clock = 0L
            val port = Fake(); val service = SourceCatalogService(port, repo) { clock }
            val first = service.browse(); val second = service.browse(cursor = first.cursor)
            assertEquals(25, first.items.size); assertEquals(25, second.items.size)
            assertTrue(first.items.map { it.chatId }.intersect(second.items.map { it.chatId }.toSet()).isEmpty())
            assertTrue(repo.sources().isEmpty())
            val selected = service.save(SaveSourceInput(1))
            assertEquals(selected.key, service.save(SaveSourceInput(1)).key)
            assertFalse(selected.monitoringEnabled); assertTrue(repo.channels().isEmpty()); assertTrue(repo.mcpSources().isEmpty()); assertTrue(repo.historySyncs().isEmpty())
            assertTrue(service.browse(filter = "groups").items.isEmpty())
            assertFailsWith<IllegalArgumentException> { service.browse(filter = "private", cursor = first.cursor) }
            clock = 180_001
            assertFailsWith<IllegalArgumentException> { service.browse(cursor = first.cursor) }
            port.fail = true
            assertEquals(SourceAccess.UNAVAILABLE, service.refresh(selected.key).access)
            assertEquals(SourceAccess.UNAVAILABLE, repo.sources().single().access)
            assertFailsWith<IllegalArgumentException> { service.save(SaveSourceInput(2)) }
            assertFailsWith<IllegalArgumentException> { service.save(SaveSourceInput(2, SourceScope(ScopeKind.MESSAGE_THREAD, messageThreadId = 42))) }
        }
    }
    @Test fun sourceScopesCannotConflateForumTopicsWithDiscussionThreads() {
        assertFailsWith<IllegalArgumentException> { SourceScope(ScopeKind.CHAT, 1).validate() }
        assertFailsWith<IllegalArgumentException> { SourceScope(ScopeKind.FORUM_TOPIC, messageThreadId = 1).validate() }
        assertFailsWith<IllegalArgumentException> { SourceScope(ScopeKind.MESSAGE_THREAD, 1, 1).validate() }
        SourceScope(ScopeKind.MESSAGE_THREAD, messageThreadId = Long.MAX_VALUE).validate()
    }
    @Test fun refreshCannotResurrectAConcurrentlyDeletedSource(): Unit = runBlocking {
        Repositories(Files.createTempDirectory("source-refresh-race-").resolve("db")).use { repo ->
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
            val source = repo.saveSource(TelegramSource(chatId = 1, type = SourceType.PRIVATE, title = "Synthetic"))
            val port = object : SourceCatalogPort {
                override suspend fun chatIds(query: String) = listOf(1L)
                override suspend fun describe(chatId: Long): TelegramSource { entered.complete(Unit); resume.await(); return source }
                override suspend fun topics(chatId: Long, cursor: TopicCursor) = TopicPage(emptyList())
                override suspend fun topic(chatId: Long, topicId: Int) = error("Unused")
            }
            val service = SourceCatalogService(port, repo)
            val result = async { runCatching { service.refresh(source.key) } }
            entered.await(); assertTrue(repo.deleteSource(source.key)); resume.complete(Unit)
            assertTrue(result.await().exceptionOrNull() is ResourceNotFound)
            assertTrue(repo.sources().isEmpty())
        }
    }

    @Test fun nativeChatLoadsAndKnownChatSearchAreBoundedDeduplicatedAndReadOnly(): Unit = runBlocking {
        val calls = mutableListOf<TdApi.Function<*>>()
        val catalog = TdlibSourceCatalog { fn ->
            calls += fn
            when (fn) {
                is TdApi.LoadChats -> { assertEquals(100, fn.limit); if (fn.chatList is TdApi.ChatListArchive) throw TelegramRequestException(404, "synthetic exhausted"); TdApi.Ok() }
                is TdApi.GetChats -> { assertEquals(500, fn.limit); TdApi.Chats().apply { chatIds = if (fn.chatList is TdApi.ChatListMain) longArrayOf(1, 2, 2) else longArrayOf(2, 3) } }
                is TdApi.SearchChats -> { assertEquals(100, fn.limit); assertNull(fn.typeFilter); TdApi.Chats().apply { chatIds = longArrayOf(3, 4) } }
                is TdApi.SearchChatsOnServer -> { assertEquals(100, fn.limit); TdApi.Chats().apply { chatIds = longArrayOf(4, 5) } }
                else -> error("Unexpected body, global search or join request")
            }
        }
        assertEquals(listOf(1L, 2L, 3L), catalog.chatIds(""))
        assertEquals(listOf(3L, 4L, 5L), catalog.chatIds("synthetic"))
        assertEquals(6, calls.size)
    }

    @Test fun forumAccessLossUpdatesSavedTopicWithoutLosingIdentity(): Unit = runBlocking {
        var banned = false
        var isForum = true
        var topicReads = 0
        var privateError = false
        var clock = 100L
        val native = TdlibSourceCatalog { fn ->
            when (fn) {
                is TdApi.GetChat -> TdApi.Chat().apply { id = -1001; title = "Synthetic forum"; type = TdApi.ChatTypeSupergroup(1, false) }
                is TdApi.GetSupergroup -> TdApi.Supergroup().apply { this.isForum = isForum; status = if (banned) TdApi.ChatMemberStatusBanned(0) else TdApi.ChatMemberStatusMember() }
                is TdApi.GetForumTopic -> { topicReads++; if (privateError) throw TelegramRequestException(400, "CHANNEL_PRIVATE"); TdApi.ForumTopic().apply { info = TdApi.ForumTopicInfo().apply { chatId = -1001; forumTopicId = 11; name = "Synthetic topic" } } }
                else -> error("Unexpected body or join request")
            }
        }
        Repositories(Files.createTempDirectory("topic-access-loss-").resolve("db")).use { repo ->
            val service = SourceCatalogService(native, repo) { clock }
            val original = service.save(SaveSourceInput(-1001, SourceScope(ScopeKind.FORUM_TOPIC, 11)))
            assertEquals(SourceAccess.AVAILABLE, original.access)
            banned = true; clock = 200
            val unavailable = service.refresh(original.key)
            assertEquals(original.key, unavailable.key); assertEquals(original.scope, unavailable.scope)
            assertEquals(SourceAccess.UNAVAILABLE, unavailable.access); assertEquals(200, unavailable.checkedAt)
            assertEquals(1, topicReads); assertEquals(SourceAccess.UNAVAILABLE, repo.sources().single().access)
            banned = false; clock = 300
            assertEquals(SourceAccess.AVAILABLE, service.refresh(original.key).access)
            isForum = false; clock = 400
            assertEquals(SourceAccess.UNAVAILABLE, service.refresh(original.key).access)
            assertEquals(2, topicReads)
            isForum = true; privateError = true; clock = 500
            assertEquals(SourceAccess.UNAVAILABLE, service.refresh(original.key).access)
            assertEquals(original.key, repo.sources().single().key)
            assertTrue(repo.channels().isEmpty()); assertTrue(repo.mcpSources().isEmpty())
        }
    }

}
