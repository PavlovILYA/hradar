package dev.radar.sources

import kotlinx.serialization.Serializable

@Serializable enum class SourceType { PRIVATE, BASIC_GROUP, SUPERGROUP, CHANNEL }
@Serializable enum class SourceAccess { AVAILABLE, UNKNOWN, UNAVAILABLE }
@Serializable enum class ScopeKind { CHAT, FORUM_TOPIC, MESSAGE_THREAD }
@Serializable data class SourceScope(val kind: ScopeKind = ScopeKind.CHAT, val forumTopicId: Int? = null, val messageThreadId: Long? = null) {
    fun validate() {
        require(when (kind) {
            ScopeKind.CHAT -> forumTopicId == null && messageThreadId == null
            ScopeKind.FORUM_TOPIC -> forumTopicId != null && forumTopicId > 0 && messageThreadId == null
            ScopeKind.MESSAGE_THREAD -> messageThreadId != null && messageThreadId > 0 && forumTopicId == null
        }) { "Некорректная область источника" }
    }
    fun nativeId(): Long = when (kind) { ScopeKind.CHAT -> 0; ScopeKind.FORUM_TOPIC -> forumTopicId!!.toLong(); ScopeKind.MESSAGE_THREAD -> messageThreadId!! }
}
@Serializable data class TelegramSource(
    val key: String = "", val chatId: Long, val type: SourceType, val title: String,
    val username: String? = null, val scope: SourceScope = SourceScope(), val scopeTitle: String? = null,
    val isPublic: Boolean = false, val access: SourceAccess = SourceAccess.UNKNOWN,
    val isForum: Boolean = false, val protectedContent: Boolean = false,
    val legacyChannel: Boolean = false, val monitoringEnabled: Boolean = false, val checkedAt: Long = 0,
)
@Serializable data class SaveSourceInput(val chatId: Long, val scope: SourceScope = SourceScope())
@Serializable data class SourcePage(val items: List<TelegramSource>, val cursor: String? = null, val notice: String? = null)
@Serializable data class TopicCursor(val date: Int = 0, val messageId: Long = 0, val forumTopicId: Int = 0)
@Serializable data class TopicPage(val items: List<TelegramSource>, val next: TopicCursor? = null)

/** Observed metadata denial, not an identity-validation or transient network error. */
internal class SourceUnavailable : IllegalArgumentException("Темы недоступны для этого чата")
