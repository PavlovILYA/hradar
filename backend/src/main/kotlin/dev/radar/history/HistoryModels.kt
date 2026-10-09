package dev.radar.history

import dev.radar.telegram.TelegramMessage
import kotlinx.serialization.Serializable

@Serializable enum class SyncStatus { IDLE, RUNNING, COMPLETED, FAILED, PAUSED }
@Serializable enum class PauseReason { USER, LIMIT }
@Serializable data class HistorySyncOptions(val maxMessages: Int? = 1000)
@Serializable data class HistorySyncRecord(
    val channelId: Long = 0,
    val username: String,
    val title: String = "",
    val status: SyncStatus = SyncStatus.IDLE,
    val cursor: Long = 0,
    val messageCount: Int = 0,
    val retryCount: Int = 0,
    val nextRetryAt: Long = 0,
    val error: String? = null,
    val updatedAt: Long = 0,
    val maxMessages: Int? = 1000,
    val pauseReason: PauseReason? = null,
)
@Serializable data class HistoryPage(val messages: List<TelegramMessage>, val nextCursor: Long?, val total: Int, val query: String)
