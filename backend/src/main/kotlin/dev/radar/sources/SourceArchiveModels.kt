package dev.radar.sources

import dev.radar.history.PauseReason
import dev.radar.history.SyncStatus
import dev.radar.research.ResearchMessage
import kotlinx.serialization.Serializable

@Serializable data class SourceSyncRecord(
    val sourceKey: String,
    val channelId: Long,
    val title: String,
    val status: SyncStatus = SyncStatus.IDLE,
    val cursor: Long = 0,
    val messageCount: Int = 0,
    val maxMessages: Int? = 1000,
    val pauseReason: PauseReason? = null,
    val retryCount: Int = 0,
    val nextRetryAt: Long = 0,
    val error: String? = null,
    val updatedAt: Long = 0,
)

@Serializable data class SourceArchivePage(
    val messages: List<ResearchMessage>, val nextCursor: Long? = null,
    val total: Int = 0, val query: String = "",
)
