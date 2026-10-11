package dev.radar.bot

import kotlinx.serialization.Serializable
import java.net.URI

/** Deliberately not a data class: credentials must never appear in toString/DTOs. */
class BotConfig private constructor(internal val token: String?, val allowedUsers: Set<Long>, val state: String, val notice: String?) {
    val enabled get() = state == "configured"
    val botId get() = token?.substringBefore(':')?.toLongOrNull()
    override fun toString() = "BotConfig(state=$state)"
    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv): BotConfig {
            val token = env("TG_BOT_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return BotConfig(null, emptySet(), "disabled", "Bot API выключен: TG_BOT_TOKEN не задан")
            val raw = env("TG_BOT_ALLOWED_USER_IDS").orEmpty().trim()
            val entries = raw.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
            val ids = entries.map { it.toLongOrNull()?.takeIf { n -> n > 0 } }
            if (entries.isEmpty() || entries.size > 100 || ids.any { it == null })
                return BotConfig(null, emptySet(), "blocked", "Укажите корректный TG_BOT_ALLOWED_USER_IDS; команды не обрабатываются")
            if (!token.matches(Regex("[1-9][0-9]*:[A-Za-z0-9_-]+")) || token.substringBefore(':').toLongOrNull() == null)
                return BotConfig(null, emptySet(), "blocked", "Некорректная конфигурация Bot API")
            return BotConfig(token, ids.filterNotNull().toSet(), "configured", null)
        }
    }
}

@Serializable data class BotButton(val text: String, val callback: String = "", val url: String? = null)
@Serializable data class BotPayload(val text: String, val buttons: List<List<BotButton>> = emptyList(), val channelId: Long? = null, val messageId: Long? = null, val needsLink: Boolean = false, val mcpSourceId: Long? = null, val editMessageId: Long? = null, val publishRequestId: String? = null, val agentJobId: String? = null)
data class BotOutboxItem(val id: Long, val userId: Long, val chatId: Long, val payload: BotPayload, val attempts: Int)
data class BotChat(val id: Long, val type: String)
data class BotMessage(val userId: Long, val chat: BotChat, val text: String?, val isBot: Boolean = false)
data class BotCallback(val id: String, val userId: Long, val chat: BotChat?, val data: String?, val isBot: Boolean = false, val messageId: Long? = null)
data class BotUpdate(val id: Long, val message: BotMessage? = null, val callback: BotCallback? = null)
data class BotIdentity(val id: Long, val username: String)
@Serializable data class BotDeliveryCounts(val pending: Int = 0, val sent: Int = 0, val failed: Int = 0)
@Serializable data class BotStatus(val state: String, val notice: String? = null, val allowedUsers: Int = 0, val registeredUsers: Int = 0, val delivery: BotDeliveryCounts = BotDeliveryCounts(), val retryAt: Long = 0, val lastPollAt: Long = 0)

/** Accept a returned Telegram HTTPS link only; never derive a post number from a TDLib ID. */
fun verifiedTelegramLink(link: String?): String? = try {
    link?.takeIf { it.length <= 512 }?.let { URI(it) }?.takeIf {
        it.scheme == "https" && it.host == "t.me" && it.userInfo == null && it.port == -1 && it.path.length > 1 && it.fragment == null
    }?.toASCIIString()
} catch (_: Exception) { null }

fun matchPayload(name: String, source: String, username: String?, text: String?, link: String?, channelId: Long, messageId: Long): BotPayload {
    val original = verifiedTelegramLink(link)
    val excerpt = text.orEmpty().replace(Regex("\\s+"), " ").trim().take(600).ifEmpty { "Публикация без текста" }
    return BotPayload("hradar · ${name.take(100)}\nИсточник: ${source.take(150)}${username?.takeIf { it.matches(Regex("[A-Za-z0-9_]{5,32}")) }?.let { " (@$it)" }.orEmpty()}\n\n$excerpt" + (original?.let { "\n\n$it" } ?: ""), buttons = original?.let { listOf(listOf(BotButton("Оригинал", url = it))) } ?: emptyList(), channelId = channelId, messageId = messageId, needsLink = original == null)
}
