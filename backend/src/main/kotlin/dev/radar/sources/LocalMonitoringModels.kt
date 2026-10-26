package dev.radar.sources

import dev.radar.research.ResearchMessage
import kotlinx.serialization.Serializable

@Serializable data class LocalSubscription(
    val id: String,
    val sourceKey: String,
    val templateId: String,
    val enabled: Boolean,
    val checkpoint: Long = 0,
    val recoveryCursor: Long = 0,
    val recoveryHead: Long = 0,
    val sinceDate: Int = 0,
    val retryAt: Long = 0,
    val error: String? = null,
    val status: String = "paused",
    val generation: Long = 0,
    val rateLimited: Boolean = false,
)

@Serializable data class LocalMatch(
    val id: String,
    val templateId: String,
    val templateName: String,
    val message: ResearchMessage,
    val read: Boolean = false,
    val sequence: Long = 0,
)

@Serializable data class LocalMatchPage(val items: List<LocalMatch>, val unread: Int, val cursor: Long? = null)
@Serializable data class ConfigureLocalMonitoring(val templateId: String, val enabled: Boolean)
