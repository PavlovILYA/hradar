package dev.radar.desktop

import kotlinx.serialization.Serializable
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

@Serializable data class ReopenInput(val nonce: String, val proof: String)

internal fun instanceProof(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
}

/** Process capability is never used as a browser launch code. */
class DesktopAccess(val capability: String) {
    private val launches = linkedSetOf<String>()
    @Synchronized fun issueLaunch(): String {
        // Rapid native reopens must not invalidate the first browser handshake.
        // Bound pending codes; the oldest is discarded after eight owner launches.
        if (launches.size == 8) launches.remove(launches.first())
        return DesktopRuntime.token().also { launches.add(it) }
    }
    @Synchronized fun consumeLaunch(value: String): Boolean = launches.remove(value)
}
