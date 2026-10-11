package dev.radar.bot

import dev.radar.research.*
import kotlinx.serialization.Serializable

/** One expiring, operator-bound workflow. At most 50 search cards are retained. */
@Serializable data class BotWorkspace(
    val nonce: String, val kind: String, val step: String = "", val query: String = "", val mode: String = "",
    val sources: List<Long> = emptyList(), val messages: List<ResearchMessage> = emptyList(), val index: Int = 0,
    val cursor: String = "", val name: String = "", val required: List<String> = emptyList(),
    val alternatives: List<String> = emptyList(), val excluded: List<String> = emptyList(),
    val pages: Int = 0, val retryAt: Long = 0, val notice: String? = null,
)
data class WorkspaceDecision(val message: ResearchMessage, val status: String)
data class WorkspaceTransition(val state: BotWorkspace?, val payload: BotPayload, val decision: WorkspaceDecision? = null, val template: SearchTemplate? = null)
