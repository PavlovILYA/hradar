package dev.radar.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable enum class AgentState { QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED, INTERRUPTED }
@Serializable data class AgentOwner(val botId: Long = 0, val userId: Long = 0)
@Serializable data class AgentEvidence(val reference: String, val channelId: Long, val messageId: Long, val date: Int, val title: String, val link: String?, val reason: String = "")
@Serializable data class AgentResult(val summary: String, val evidence: List<AgentEvidence>, val notice: String = "AI может ошибаться. Проверяйте исходные публикации; охват ограничен разрешёнными источниками.")
@Serializable data class AgentJob(val id: String, val owner: AgentOwner, val question: String, val sources: List<Long>, val policy: String, val state: AgentState, val createdAt: Long, val updatedAt: Long, val result: AgentResult? = null, val error: String? = null, val delivered: Boolean = false, val limits: AgentLimits? = null)
@Serializable data class AgentConsent(val sources: List<Long>, val policy: String, val enabled: Boolean)
@Serializable data class AgentAnswer(val summary: String, val evidence: List<AgentCitation>)
@Serializable data class AgentCitation(val reference: String, val reason: String)
@Serializable data class AgentToolInput(val chat: String, val query: String? = null, val cursor: String? = null, val messageId: Long? = null)
data class AgentToolCall(val id: String, val name: String, val arguments: JsonObject)
data class AgentTurn(val output: List<JsonObject>, val calls: List<AgentToolCall>, val answer: AgentAnswer?, val inputTokens: Int, val outputTokens: Int)

/** No credentials, process, Telegram client or repository crosses this boundary. */
fun interface AgentPort { suspend fun respond(request: AgentModelRequest): AgentTurn }
data class AgentModelRequest(val model: String, val input: List<JsonObject>, val maxOutputTokens: Int)
class AgentFailure(val code: String, val retryAfterSeconds: Long? = null) : RuntimeException(code)

@Serializable data class AgentLimits(val model: String = "", val maxOutputTokens: Int = 2048, val tokenBudget: Int = 32000,
    val budgetUsd: Double = 0.5, val inputUsdPerMillion: Double = 0.0, val outputUsdPerMillion: Double = 0.0,
    val maxToolCalls: Int = 12, val timeoutSeconds: Int = 120, val queueSize: Int = 5, val retentionDays: Int = 30) {
    init {
        require(model.length <= 100 && model.none { it.isISOControl() })
        require(maxOutputTokens in 256..8192 && tokenBudget in maxOutputTokens..100000)
        require(budgetUsd.isFinite() && budgetUsd in 0.01..10.0)
        require(inputUsdPerMillion.isFinite() && inputUsdPerMillion in 0.0..1000.0 && outputUsdPerMillion.isFinite() && outputUsdPerMillion in 0.0..1000.0)
        require(maxToolCalls in 1..20 && timeoutSeconds in 5..120 && queueSize in 1..5 && retentionDays in 1..90)
    }
    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv): AgentLimits {
            fun value(name: String) = env(name)?.trim()?.takeIf { it.isNotBlank() }
            return AgentLimits(
                model = value("HRADAR_AI_MODEL").orEmpty(), maxOutputTokens = value("HRADAR_AI_MAX_OUTPUT_TOKENS")?.toInt() ?: 2048,
                tokenBudget = value("HRADAR_AI_TOKEN_BUDGET")?.toInt() ?: 32000, budgetUsd = value("HRADAR_AI_BUDGET_USD")?.toDouble() ?: 0.5,
                inputUsdPerMillion = value("HRADAR_AI_INPUT_USD_PER_MILLION")?.toDouble() ?: 0.0,
                outputUsdPerMillion = value("HRADAR_AI_OUTPUT_USD_PER_MILLION")?.toDouble() ?: 0.0)
        }
    }
}
