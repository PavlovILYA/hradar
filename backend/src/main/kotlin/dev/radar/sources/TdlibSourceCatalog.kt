package dev.radar.sources

import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi

interface SourceCatalogPort {
    suspend fun chatIds(query: String): List<Long>
    suspend fun describe(chatId: Long): TelegramSource?
    suspend fun topics(chatId: Long, cursor: TopicCursor): TopicPage
    suspend fun topic(chatId: Long, topicId: Int): TelegramSource
}

/** Metadata only: never return native Chat/ForumTopic (lastMessage and drafts). */
class TdlibSourceCatalog(private val request: suspend (TdApi.Function<*>) -> TdApi.Object) : SourceCatalogPort {
    override suspend fun chatIds(query: String): List<Long> = withTimeout(20_000) {
        if (query.isNotEmpty()) {
            val local = request(TdApi.SearchChats(query, null, 100)) as TdApi.Chats
            val remote = request(TdApi.SearchChatsOnServer(query, null, 100)) as TdApi.Chats
            return@withTimeout (local.chatIds.toList() + remote.chatIds.toList()).distinct()
        }
        val ids = mutableListOf<Long>()
        for (list in listOf(TdApi.ChatListMain(), TdApi.ChatListArchive())) {
            try { request(TdApi.LoadChats(list, 100)) }
            catch (ex: TelegramRequestException) { if (ex.code != 404) throw ex }
            ids += (request(TdApi.GetChats(list, 500)) as TdApi.Chats).chatIds.toList()
        }
        ids.distinct().take(1000)
    }
    private fun access(status: TdApi.ChatMemberStatus?): SourceAccess = when (status) {
        is TdApi.ChatMemberStatusBanned -> SourceAccess.UNAVAILABLE
        is TdApi.ChatMemberStatusMember, is TdApi.ChatMemberStatusAdministrator -> SourceAccess.AVAILABLE
        is TdApi.ChatMemberStatusCreator -> if (status.isMember) SourceAccess.AVAILABLE else SourceAccess.UNKNOWN
        is TdApi.ChatMemberStatusRestricted -> if (status.isMember) SourceAccess.AVAILABLE else SourceAccess.UNKNOWN
        else -> SourceAccess.UNKNOWN
    }
    override suspend fun describe(chatId: Long): TelegramSource? {
        val chat = request(TdApi.GetChat(chatId)) as TdApi.Chat
        require(chat.id == chatId) { "Telegram вернул другой чат" }
        var username: String? = null
        var isForum = false
        val type: SourceType
        val state: SourceAccess
        when (val t = chat.type) {
            is TdApi.ChatTypeSecret -> return null
            is TdApi.ChatTypePrivate -> {
                val user = request(TdApi.GetUser(t.userId)) as TdApi.User
                username = user.usernames?.activeUsernames?.firstOrNull()
                type = SourceType.PRIVATE
                state = if (user.type is TdApi.UserTypeDeleted) SourceAccess.UNAVAILABLE else SourceAccess.AVAILABLE
            }
            is TdApi.ChatTypeBasicGroup -> {
                val group = request(TdApi.GetBasicGroup(t.basicGroupId)) as TdApi.BasicGroup
                type = SourceType.BASIC_GROUP
                state = if (!group.isActive) SourceAccess.UNAVAILABLE else access(group.status)
            }
            is TdApi.ChatTypeSupergroup -> {
                val group = request(TdApi.GetSupergroup(t.supergroupId)) as TdApi.Supergroup
                if (group.isDirectMessagesGroup) return null // Distinct topic kind; not a normal group.
                username = group.usernames?.activeUsernames?.firstOrNull()
                type = if (group.isChannel) SourceType.CHANNEL else SourceType.SUPERGROUP
                isForum = group.isForum && !group.isChannel
                state = access(group.status)
            }
            else -> return null
        }
        return TelegramSource(chatId = chatId, type = type, title = chat.title.orEmpty().take(256), username = username?.take(32),
            isPublic = type in setOf(SourceType.CHANNEL, SourceType.SUPERGROUP) && username != null,
            access = state, isForum = isForum, protectedContent = chat.hasProtectedContent)
    }
    private suspend fun forum(chatId: Long): TelegramSource {
        val source = describe(chatId)
        require(source?.isForum == true && source.access != SourceAccess.UNAVAILABLE) { "Темы недоступны для этого чата" }
        return source
    }
    override suspend fun topics(chatId: Long, cursor: TopicCursor): TopicPage {
        val source = forum(chatId)
        val topics = request(TdApi.GetForumTopics(chatId, "", cursor.date, cursor.messageId, cursor.forumTopicId, 25)) as TdApi.ForumTopics
        val next = TopicCursor(topics.nextOffsetDate, topics.nextOffsetMessageId, topics.nextOffsetForumTopicId)
        return TopicPage(topics.topics.map { scoped(source, it) }, next.takeIf { it != TopicCursor() && it != cursor })
    }
    override suspend fun topic(chatId: Long, topicId: Int): TelegramSource =
        scoped(forum(chatId), request(TdApi.GetForumTopic(chatId, topicId)) as TdApi.ForumTopic).also {
            require(it.scope.forumTopicId == topicId) { "Telegram вернул другую тему" }
        }
    private fun scoped(source: TelegramSource, topic: TdApi.ForumTopic): TelegramSource {
        require(topic.info.chatId == source.chatId && topic.info.forumTopicId > 0) { "Тема не принадлежит чату" }
        return source.copy(scope = SourceScope(ScopeKind.FORUM_TOPIC, topic.info.forumTopicId), scopeTitle = topic.info.name.orEmpty().take(256))
    }
}
