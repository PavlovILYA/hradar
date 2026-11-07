package dev.radar.research

import kotlinx.serialization.Serializable

@Serializable
data class MonitoredChannel(val id: Long, val username: String, val title: String)
@Serializable
data class SearchTemplate(
    val id: String = "",
    val name: String,
    val required: List<String> = emptyList(),
    val any: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val telegramQuery: String,
    val sourceMode: String = "all",
    val sources: List<Long> = emptyList(),
    val active: Boolean = true,
    val subscribed: Boolean = false,
    val subscriptionSince: Int = 0,
)
@Serializable
data class MatchExplanation(val matches: Boolean, val foundRequired: List<String>, val foundAny: List<String>, val foundExcluded: List<String>, val missingRequired: List<String>)
@Serializable
data class ResearchMessage(val id: Long, val channelId: Long, val date: Int, val text: String?, val title: String, val username: String?, val link: String? = null, val explanation: MatchExplanation? = null, val sourceKey: String? = null, val forumTopicId: Int? = null, val scopeTitle: String? = null, val availability: String = "available", val messageThreadId: Long? = null, val canGetMessageThread: Boolean? = null)
@Serializable
data class SearchRequest(val mode: String = "channel", val query: String = "", val usernames: List<String> = emptyList(), val templateId: String? = null, val limit: Int = 20, val cursor: String = "", val withLinks: Boolean = true, val sourceKeys: List<String> = emptyList())
@Serializable
data class SearchPage(val messages: List<ResearchMessage> = emptyList(), val cursor: String = "", val state: String = "ok", val notice: String? = null)
@Serializable
data class NotificationRecord(val id: String, val templateId: String, val templateName: String, val message: ResearchMessage, val read: Boolean = false, val sequence: Long = 0)
@Serializable
data class MonitoringState(val channelId: Long, val lastMessageId: Long = 0, val recoveryCursor: Long = 0, val recoveryHead: Long = 0, val error: String? = null, val retryAt: Long = 0)
