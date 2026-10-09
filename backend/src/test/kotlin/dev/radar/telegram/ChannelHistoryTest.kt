package dev.radar.telegram

import dev.radar.api.messageLimit
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ChannelHistoryTest {
    @Test fun validatesLimit() {
        assertEquals(10, messageLimit(null))
        assertEquals(100, messageLimit("100"))
        for (value in listOf("abc", "0", "101", "")) {
            assertFailsWith<IllegalArgumentException> { messageLimit(value) }
        }
    }

    private fun chat(channel: Boolean = true) = TdApi.Chat().apply {
        id = -100123L
        type = TdApi.ChatTypeSupergroup(123L, channel)
    }

    private fun message(id: Long, content: TdApi.MessageContent) = TdApi.Message().apply {
        this.id = id
        date = 123456
        this.content = content
    }

    @Test fun paginatesShortPagesAndPreservesCaptions() = runBlocking {
        val text = message(30L, TdApi.MessageText().apply { text = TdApi.FormattedText("post", emptyArray()) })
        val photo = message(20L, TdApi.MessagePhoto().apply { caption = TdApi.FormattedText("caption", emptyArray()) })
        val sticker = message(10L, TdApi.MessageSticker())
        val cursors = mutableListOf<Long>()
        val result = readChannelHistory("telegram", 3) { query ->
            when (query) {
                is TdApi.SearchPublicChat -> { assertEquals("telegram", query.username); chat() }
                is TdApi.GetChatHistory -> {
                    assertFalse(query.onlyLocal)
                    assertEquals(-100123L, query.chatId)
                    cursors.add(query.fromMessageId)
                    TdApi.Messages().apply { messages = when (query.fromMessageId) {
                        0L -> arrayOf(text)
                        30L -> arrayOf(text, photo)
                        else -> arrayOf(photo, sticker)
                    } }
                }
                else -> error("Unexpected request")
            }
        }
        assertEquals(listOf(0L, 30L, 20L), cursors)
        assertEquals(listOf(30L, 20L, 10L), result.map { it.id })
        assertEquals(listOf("post", "caption", null), result.map { it.text })
        assertEquals(-100123L, result.first().channelId)
        assertEquals(123456, result.first().date)
    }

    @Test fun stopsWhenHistoryDoesNotAdvance() = runBlocking {
        var requests = 0
        val result = readChannelHistory("telegram", 10) { query ->
            if (query is TdApi.SearchPublicChat) chat() else {
                requests++
                TdApi.Messages().apply { messages = arrayOf(message(1L, TdApi.MessageSticker())) }
            }
        }
        assertEquals(2, requests)
        assertEquals(1, result.size)
    }

    @Test fun rejectsGroups() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { readChannelHistory("group", 10) { chat(false) } }
        }
    }

    @Test fun propagatesUnavailableChannel() {
        val error = assertFailsWith<TelegramRequestException> {
            runBlocking { readChannelHistory("missing", 10) { throw TelegramRequestException(400, "USERNAME_NOT_OCCUPIED") } }
        }
        assertEquals("USERNAME_NOT_OCCUPIED", error.reason)
    }
}
