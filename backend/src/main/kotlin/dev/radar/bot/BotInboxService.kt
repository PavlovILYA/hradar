package dev.radar.bot

import dev.radar.research.*

/** Saved publications only; no online search, joins or history imports. */
class BotInboxService(private val repo: Repositories) {
    fun matches(before: Long = Long.MAX_VALUE, botId: Long? = null, user: Long? = null): BotPayload {
        val page = repo.recentMatches(11, before, botId = botId, user = user)
        val records = page.take(10)
        val rows = records.map { listOf(BotButton("${if (it.read) "✓" else "●"} ${it.templateName.take(35)} · ${it.message.title.take(25)}", "match:${it.id}")) }.toMutableList()
        if (page.size > 10) rows.add(listOf(BotButton("Ранее →", "matches:${records.last().sequence}")))
        if (before != Long.MAX_VALUE) rows.add(listOf(BotButton("К новым", "matches:${Long.MAX_VALUE}")))
        return BotPayload(if (records.isEmpty()) "Совпадений пока нет." else "Сохранённые совпадения: выберите публикацию", rows)
    }
    fun details(id: String, read: Boolean = false, botId: Long? = null, user: Long? = null): BotPayload {
        val record = repo.match(id) ?: return BotPayload("Совпадение больше недоступно")
        if (botId != null && user != null && repo.publicationStatus(botId, user, record.message.channelId, record.message.id) == "REJECTED") return BotPayload("Публикация отмечена как «Не подходит»")
        if (read) repo.markRead(id)
        val newer = repo.newerMatch(record.sequence, botId, user)
        val older = repo.recentMatches(1, record.sequence, botId = botId, user = user).firstOrNull()
        val navigation = buildList {
            newer?.let { add(BotButton("← Новее", "match:${it.id}")) }
            older?.let { add(BotButton("Ранее →", "match:${it.id}")) }
        }
        return inboxPayload(record.copy(read = record.read || read)).let {
            it.copy(buttons = it.buttons + listOf(navigation).filter { row -> row.isNotEmpty() } + listOf(listOf(BotButton("К списку", "matches:${Long.MAX_VALUE}"))))
        }
    }
    fun sources(offset: Int = 0): BotPayload {
        require(offset in 0..10000)
        val all = repo.channels()
        val page = all.drop(offset).take(15)
        val navigation = buildList {
            if (offset > 0) add(BotButton("← Назад", "sources:${(offset - 15).coerceAtLeast(0)}"))
            if (offset + 15 < all.size) add(BotButton("Далее →", "sources:${offset + 15}"))
        }
        return BotPayload(if (all.isEmpty()) "Нет сохранённых источников." else "Сохранённые источники (${offset + 1}–${offset + page.size} из ${all.size}):\n" + page.joinToString("\n") { "${it.title.take(100)} · @${it.username.take(32)}" }, listOf(navigation).filter { it.isNotEmpty() })
    }
    fun digest(): BotPayload {
        val counts = repo.unreadByChannel()
        val sources = repo.channels().associateBy { it.id }
        val page = counts.entries.sortedByDescending { it.value }.take(15)
        return BotPayload("Непрочитанных совпадений: ${repo.unreadCount()}\n" + page.joinToString("\n") { "${sources[it.key]?.title?.take(100) ?: "Удалённый источник"}: ${it.value}" }, listOf(listOf(BotButton("Открыть Inbox", "matches:${Long.MAX_VALUE}"))))
    }
}

fun inboxPayload(record: NotificationRecord): BotPayload {
    val m = record.message
    val payload = matchPayload(record.templateName, m.title, m.username, m.text, m.link, m.channelId, m.id)
    val buttons = buildList {
        if (!record.read) add(listOf(BotButton("Прочитано", "read:${record.id}")))
        verifiedTelegramLink(m.link)?.let { add(listOf(BotButton("Оригинал", url = it))) }
    }
    return payload.copy(text = (if (record.read) "✓ Прочитано\n" else "● Не прочитано\n") + payload.text, buttons = buttons)
}
