package dev.radar.bot

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object BotInvitationToken {
    private val random = SecureRandom()
    private val pattern = Regex("i_[A-Za-z0-9_-]{43}")
    fun create(): String = "i_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun hash(token: String): String? = if (pattern.matches(token) && token.length <= 64)
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
    else null
}
