package dev.radar.capabilities

import dev.radar.research.*
import dev.radar.telegram.*
import org.drinkless.tdlib.TdApi

/** Narrow, typed test seam. Never exported as a tool or a low-level function executor. */
interface TelegramReadPort {
    fun status(): TelegramStatus
    suspend fun resolve(username: String): MonitoredChannel
    suspend fun history(channelId: Long, cursor: Long): List<TelegramMessage>
    suspend fun search(username: String, query: String, limit: Int, cursor: String): SearchPage
    suspend fun message(channel: MonitoredChannel, id: Long): ResearchMessage
    suspend fun neighbors(channel: MonitoredChannel, id: Long, count: Int): List<ResearchMessage>
}
class TdlibReadPort(private val gateway: TdlibGateway, private val searchService: SearchService) : TelegramReadPort {
    override fun status() = gateway.getStatus()
    override suspend fun resolve(username: String) = gateway.resolveChannel(username)
    override suspend fun history(channelId: Long, cursor: Long) = gateway.getHistoryPage(channelId, cursor)
    override suspend fun search(username: String, query: String, limit: Int, cursor: String) = searchService.search(SearchRequest("channel", query, listOf(username), limit = limit, cursor = cursor))
    override suspend fun message(channel: MonitoredChannel, id: Long): ResearchMessage {
        val result = gateway.executeRead(TdApi.GetMessage(channel.id, id)) as TdApi.Message
        return searchService.enrich(result, channel)
    }
    override suspend fun neighbors(channel: MonitoredChannel, id: Long, count: Int): List<ResearchMessage> {
        // TDLib allows negative offset to include nearby newer messages; positive offsets are invalid.
        val page = gateway.executeRead(TdApi.GetChatHistory(channel.id, id, -count, count * 2 + 1, false)) as TdApi.Messages
        return page.messages.filter { it.id != id }.take(count * 2).map { searchService.enrich(it, channel, withLink = false) }
    }
}
