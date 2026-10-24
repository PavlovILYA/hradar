package dev.radar.telegram

import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class PublicUserLookupTest {
    @Test fun resolvesNumericUserIdAndRejectsChannelsAndMissingNames() = runBlocking {
        val chat = TdApi.Chat().apply { type = TdApi.ChatTypePrivate(8) }
        val user = TdApi.User().apply {
            id = 8; firstName = "Guest"; lastName = "Person"; type = TdApi.UserTypeRegular()
            usernames = TdApi.Usernames().apply { activeUsernames = arrayOf("guest_user") }
        }
        val found = readPublicUser("guest_user") { query -> when (query) { is TdApi.SearchPublicChat -> chat; is TdApi.GetUser -> user; else -> error("Unexpected request") } }
        assertEquals(8, found.id)
        assertEquals("Guest Person", found.name)
        assertEquals("guest_user", found.username)
        chat.type = TdApi.ChatTypeSupergroup().apply { isChannel = true }
        assertFailsWith<IllegalArgumentException> { readPublicUser("channel_name") { chat } }
        assertFailsWith<TelegramRequestException> { readPublicUser("missing_user") { throw TelegramRequestException(404, "USERNAME_NOT_OCCUPIED") } }
    }
}
