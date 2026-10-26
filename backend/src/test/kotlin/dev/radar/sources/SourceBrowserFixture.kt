package dev.radar.sources

import dev.radar.ApiError
import dev.radar.respondApiError
import dev.radar.research.*
import dev.radar.telegram.TdlibGateway
import dev.radar.telegram.TelegramRequestException
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.drinkless.tdlib.TdApi
import java.nio.file.Files

/** Real local REST/services/SQLite for browser acceptance; never creates a native client. */
object SourceBrowserFixture {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Files.createTempDirectory("hradar-source-browser-")
        val repo = Repositories(directory.resolve("research.sqlite"))
        repo.saveSource(TelegramSource(chatId = -2002, type = SourceType.SUPERGROUP, title = "Synthetic local forum", scope = SourceScope(ScopeKind.FORUM_TOPIC, 17), scopeTitle = "Synthetic hiring topic", isForum = true, access = SourceAccess.AVAILABLE))
        repo.saveSource(TelegramSource(chatId = 3001, type = SourceType.PRIVATE, title = "Synthetic cloud chat", access = SourceAccess.AVAILABLE))
        val message = TdApi.Message().apply {
            id = 900; chatId = -2002; date = 1700000000; topicId = TdApi.MessageTopicForum(17)
            content = TdApi.MessageText().apply { text = TdApi.FormattedText("Synthetic Java developer looking for work with experience", emptyArray()) }
        }
        val native: suspend (TdApi.Function<*>) -> TdApi.Object = { rpc -> when (rpc) {
            is TdApi.GetChat -> TdApi.Chat().apply { id = rpc.chatId; title = if (id == -2002L) "Synthetic local forum" else "Synthetic cloud chat"; type = if (id == -2002L) TdApi.ChatTypeSupergroup(2002, false) else TdApi.ChatTypePrivate(3001) }
            is TdApi.GetSupergroup -> TdApi.Supergroup().apply { id = 2002; isForum = true; status = TdApi.ChatMemberStatusMember() }
            is TdApi.GetUser -> TdApi.User().apply { id = 3001; type = TdApi.UserTypeRegular() }
            is TdApi.GetForumTopic -> TdApi.ForumTopic().apply { info = TdApi.ForumTopicInfo().apply { chatId = -2002; forumTopicId = 17; name = "Synthetic hiring topic" } }
            is TdApi.GetForumTopicHistory -> { check(rpc.chatId == -2002L && rpc.forumTopicId == 17); TdApi.Messages().apply { messages = if (rpc.fromMessageId == 0L) arrayOf(message) else emptyArray() } }
            is TdApi.SearchChatMessages -> { check(rpc.chatId == -2002L && (rpc.topicId as TdApi.MessageTopicForum).forumTopicId == 17); TdApi.FoundChatMessages().apply { messages = arrayOf(message); nextFromMessageId = 0 } }
            is TdApi.GetMessage -> { check(rpc.chatId == -2002L && rpc.messageId == 900L); message }
            is TdApi.GetMessageProperties -> TdApi.MessageProperties()
            is TdApi.GetMessageLink -> throw TelegramRequestException(400, "MESSAGE_LINK_UNAVAILABLE")
            else -> error("Unexpected fake RPC type")
        } }
        val matching = MatchingService()
        val gateway = TdlibGateway(1, "unused", directory)
        val search = SearchService(gateway, repo, matching, native)
        val reader = SourceReadService(repo, native)
        val archive = SourceArchiveService(repo, { key, cursor -> reader.recent(key, cursor, 100) }, { true }, pageDelayMs = 0, requestIntervalMs = 0)
        val projects = RecruitmentProjectService(repo, matching, SubscriptionService(repo, matching), RecruiterService(repo, RecruiterSearchPort(search::search)), localSearch = LocalProjectSearchService(repo, RecruiterSearchPort(search::search)))
        Runtime.getRuntime().addShutdownHook(Thread { archive.close(); repo.close() })
        embeddedServer(Netty, host = "127.0.0.1", port = 18082) {
            routing { install(ContentNegotiation) { json(Json { encodeDefaults = true }) } }
            install(StatusPages) {
                exception<ResourceNotFound> { call, _ -> call.respondApiError(HttpStatusCode.NotFound, ApiError("Ресурс не найден")) }
                exception<IllegalArgumentException> { call, ex -> call.respondApiError(HttpStatusCode.BadRequest, ApiError(ex.message ?: "Некорректный запрос")) }
                exception<TelegramRequestException> { call, _ -> call.respondApiError(HttpStatusCode.BadGateway, ApiError("Telegram недоступен")) }
            }
            configureSourceRoutes(SourceCatalogService(TdlibSourceCatalog(native), repo), repo, reader, archive)
            configureResearchRoutes(gateway, repo, matching, search, NotificationService(repo, matching))
            configureRecruitmentRoutes(projects)
        }.start(wait = true)
    }
}
