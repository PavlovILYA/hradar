package dev.radar.bot

import dev.radar.research.*
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Telegram UI state machine. Network reads happen before the short update transaction. */
class BotWorkspaceService(
    private val repo: Repositories, private val recruiter: RecruiterService,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private fun fresh(kind: String) = BotWorkspace(UUID.randomUUID().toString().replace("-", "").take(12), kind)
    private fun button(state: BotWorkspace, label: String, action: String) = BotButton(label, "w:${state.nonce}:$action")
    private fun transition(state: BotWorkspace?, text: String, buttons: List<List<BotButton>> = emptyList()) = WorkspaceTransition(state, BotPayload(text, buttons))
    private fun sourceMenu(state: BotWorkspace): WorkspaceTransition {
        val channels = repo.channels()
        val rows = channels.map { c -> listOf(button(state, "${if (c.id in state.sources) "✓ " else ""}${c.title.take(45)}", "source:${c.id}")) }.toMutableList()
        rows.add(0, listOf(button(state, "Все сохранённые (${channels.size})", "all")))
        if (state.kind == "watch") rows.add(listOf(button(state, "Предпросмотр", "preview")))
        return transition(state, if (channels.isEmpty()) "Нет сохранённых источников. Добавьте публичный источник в hradar." else if (state.kind == "watch") "Выберите источники подписки, затем «Предпросмотр»." else "${if (state.mode == "archive") "Локальный архив" else "Telegram"}: выберите сохранённый источник или все. Поиск не охватывает весь Telegram.", rows)
    }
    private fun modeMenu(state: BotWorkspace) = transition(state, "Запрос: ${state.query}\nГде искать? Только сохранённые публичные источники.", listOf(listOf(button(state, "В Telegram", "mode:telegram"), button(state, "В локальном архиве", "mode:archive"))))
    private fun visible(botId: Long, user: Long, messages: List<ResearchMessage>) = messages.filter { repo.publicationStatus(botId, user, it.channelId, it.id) !in setOf("REJECTED", "SAVED", "VIEWED") }
    private suspend fun load(botId: Long, user: Long, state: BotWorkspace): WorkspaceTransition {
        if (state.retryAt > now()) return transition(state, "Telegram ограничил поиск. Повторите через ${(state.retryAt - now() + 999) / 1000} сек.", listOf(listOf(button(state, "Повторить", "more"))))
        if (state.pages >= 5) return transition(state.copy(cursor = ""), "Достигнут лимит: 5 страниц, до 50 публикаций. Уточните запрос через /find.")
        val result = withTimeout(25_000) { recruiter.find(state.query, state.mode, state.sources, state.cursor, 10) }
        val ids = state.messages.map { it.channelId to it.id }.toSet()
        val added = visible(botId, user, result.messages).filter { (it.channelId to it.id) !in ids }
        val messages = (state.messages + added).take(50)
        val next = result.cursor.takeIf { it != state.cursor && state.pages + 1 < 5 && messages.size < 50 }.orEmpty()
        val updated = state.copy(messages = messages, index = if (added.isNotEmpty()) state.messages.size else state.index, cursor = next, pages = state.pages + 1, retryAt = 0, notice = result.notice?.take(400))
        return card(botId, user, updated)
    }
    private fun card(botId: Long, user: Long, state: BotWorkspace): WorkspaceTransition {
        val message = state.messages.getOrNull(state.index)
        val rows = mutableListOf<List<BotButton>>()
        val navigation = buildList {
            if (state.index > 0) add(button(state, "← Предыдущий", "prev"))
            if (state.index + 1 < state.messages.size) add(button(state, "Следующий →", "next"))
            else if (state.cursor.isNotEmpty()) add(button(state, "Следующая страница →", if (state.kind == "saved") "savedMore" else "more"))
        }
        if (navigation.isNotEmpty()) rows.add(navigation)
        if (message == null && state.kind == "saved") return transition(state, "Избранное пусто на этой странице. /find — поиск публикаций.", rows)
        if (message == null) return transition(state, "Нет необработанных результатов по выбранным источникам.\n${state.notice.orEmpty()}\nСохранённые и отклонённые публикации повторно не показаны. /saved — избранное; /find — новый запрос.", rows)
        if (state.kind == "find" && repo.channels().none { it.id == message.channelId }) return transition(state, "Источник больше не сохранён. Начните /find заново.")
        val status = repo.publicationStatus(botId, user, message.channelId, message.id)
        rows.add(listOf(button(state, if (status == "SAVED") "Убрать из сохранённого" else "Сохранить", if (status == "SAVED") "remove" else "save"), button(state, "Не подходит", "dismiss")))
        if (state.query.isNotBlank()) rows.add(listOf(button(state, "Создать подписку", "watch")))
        verifiedTelegramLink(message.link)?.let { rows.add(listOf(BotButton("Открыть оригинал", url = it))) }
        val label = if (state.kind == "saved") "Избранное" else if (state.mode == "archive") "Локальный архив" else "Telegram · выбранные источники"
        return WorkspaceTransition(state, BotPayload("$label · ${state.index + 1}/${state.messages.size}\n${message.title.take(120)}\n${if (status == "SAVED") "✓ Сохранено\n" else ""}\n${message.text.orEmpty().take(2200).ifBlank { "Публикация без текста" }}\n\n${state.notice.orEmpty()}", rows, message.channelId, message.id, verifiedTelegramLink(message.link) == null && repo.channels().any { it.id == message.channelId }))
    }
    private fun terms(text: String): List<String> {
        if (text == "-") return emptyList()
        val words = text.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct()
        require(words.size <= 20 && words.all { it.length <= 100 } && words.sumOf { it.length } <= 700)
        return words
    }
    private fun watchStart(state: BotWorkspace = fresh("watch")) = transition(state.copy(kind = "watch", step = "name", messages = emptyList(), cursor = ""), "Создание подписки. Введите название фильтра (до 100 символов). /cancel — отменить.")
    private fun watchTemplate(state: BotWorkspace) = SearchTemplate(name = state.name, required = state.required, any = state.alternatives, excluded = state.excluded, telegramQuery = state.required.firstOrNull() ?: state.alternatives.firstOrNull().orEmpty(), sourceMode = "selected", sources = state.sources, subscribed = true)
    private fun preview(state: BotWorkspace): WorkspaceTransition {
        val template = MatchingService().validate(watchTemplate(state))
        require(repo.channels().count { it.id in template.sources } == template.sources.size)
        val updated = state.copy(step = "confirm")
        return transition(updated, "Подписка: ${template.name}\nОбязательные (AND): ${template.required.joinToString(", ")}\nАльтернативы (OR): ${template.any.joinToString(", ").ifBlank { "—" }}\nИсключения (NOT): ${template.excluded.joinToString(", ").ifBlank { "—" }}\nИсточники: ${repo.channels().filter { it.id in template.sources }.joinToString { "@${it.username}" }}\nЗапрос Telegram: ${template.telegramQuery}\nМониторинг новых публикаций, без импорта истории. Подписка общая с React.", listOf(listOf(button(updated, "Подтвердить подписку", "confirm"), button(updated, "Изменить источники", "sources")), listOf(button(updated, "Отмена", "cancel"))))
    }
    private fun watchText(state: BotWorkspace, text: String): WorkspaceTransition = when (state.step) {
        "name" -> {
            require(text.length in 1..100)
            transition(state.copy(name = text, step = "required"), "Обязательные слова или фразы через запятую (AND). '-' — пропустить. ${state.query.takeIf { it.isNotBlank() }?.let { "Для запроса «$it» введите нужные слова явно." }.orEmpty()}")
        }
        "required" -> transition(state.copy(required = terms(text), step = "alternatives"), "Альтернативные слова или фразы через запятую (OR). '-' — без альтернатив.")
        "alternatives" -> {
            val any = terms(text); require(state.required.isNotEmpty() || any.isNotEmpty())
            transition(state.copy(alternatives = any, step = "excluded"), "Исключения через запятую (NOT). '-' — без исключений.")
        }
        "excluded" -> sourceMenu(state.copy(excluded = terms(text), step = "sources"))
        else -> transition(state, "Используйте кнопки текущего диалога или /cancel.")
    }
    private fun saved(botId: Long, user: Long, state: BotWorkspace, before: Long = Long.MAX_VALUE): WorkspaceTransition {
        val page = recruiter.saved(botId, user, before)
        return card(botId, user, state.copy(kind = "saved", messages = page.items.take(10).map { it.message }, index = 0, cursor = page.nextCursor?.toString().orEmpty(), notice = null))
    }
    private fun digest(state: BotWorkspace, botId: Long, user: Long, period: String, before: Long = Long.MAX_VALUE): WorkspaceTransition {
        require(period in setOf("today", "week"))
        val instant = Instant.ofEpochMilli(now()); val local = instant.atZone(zone)
        val since = if (period == "today") local.toLocalDate().atStartOfDay(zone).toEpochSecond() else instant.minusSeconds(7 * 86400).epochSecond
        val page = repo.matchDigest(since.toInt(), (instant.epochSecond + 1).toInt(), 10, before, botId, user)
        val updated = state.copy(kind = "digest", mode = period, step = "list")
        val rows = page.matches.map { listOf(BotButton("${if (it.read) "✓" else "●"} ${it.templateName.take(25)} · ${it.message.title.take(30)}", "match:${it.id}")) }.toMutableList()
        page.nextCursor?.let { rows.add(listOf(button(updated, "Ранее →", "digestMore:$it"))) }
        rows.add(listOf(button(updated, "Сегодня", "period:today"), button(updated, "Последние 7 дней", "period:week")))
        return transition(updated, "Дайджест · ${if (period == "today") "сегодня (${zone.id})" else "последние 7 дней"}\nСохранённых совпадений за период: ${page.total}\nНепросмотренных: ${page.unread}\n${if (page.matches.isEmpty()) "Нет совпадений за период." else "Выберите публикацию."}", rows)
    }
    suspend fun prepare(botId: Long, user: Long, raw: String?, callback: BotCallback?): WorkspaceTransition? {
        val transition = prepareStep(botId, user, raw, callback) ?: return null
        val state = transition.state ?: return transition
        // Rotate every rendered keyboard: stale cards cannot save/dismiss a different selection.
        val updated = state.copy(nonce = fresh(state.kind).nonce)
        val buttons = transition.payload.buttons.map { row -> row.map { b ->
            if (b.callback.startsWith("w:${state.nonce}:")) b.copy(callback = b.callback.replaceFirst("w:${state.nonce}:", "w:${updated.nonce}:")) else b
        } }
        return transition.copy(state = updated, payload = transition.payload.copy(buttons = buttons))
    }
    private suspend fun prepareStep(botId: Long, user: Long, raw: String?, callback: BotCallback?): WorkspaceTransition? {
        var state = repo.workspace(botId, user, now())
        try {
            if (raw != null && raw.startsWith('/')) {
                val parts = raw.split(Regex("\\s+"), limit = 2)
                val command = parts[0].substringBefore('@'); val argument = parts.getOrNull(1).orEmpty().trim()
                when (command) {
                    "/find" -> {
                        if (argument.length !in 1..256) return transition(state, "Используйте /find java spring remote (до 256 символов).")
                        return modeMenu(fresh("find").copy(query = argument, step = "mode"))
                    }
                    "/watch" -> return watchStart()
                    "/saved" -> return saved(botId, user, fresh("saved"))
                    "/digest" -> return digest(fresh("digest"), botId, user, "today")
                    "/cancel" -> return transition(null, "Диалог отменён. /find · /watch · /saved · /digest")
                    "/ask" -> return transition(state, "Агентный режим пока не подключён. Доступны /find, /watch, /saved и /digest.")
                    else -> return null
                }
            }
            if (callback == null) return if (state?.kind == "watch" && raw != null) watchText(state, raw) else null
            val parts = callback.data.orEmpty().split(':')
            if (parts.firstOrNull() != "w") return null
            if (state == null || parts.size !in 3..4 || parts[1] != state.nonce) return transition(state, "Кнопка устарела. Начните /find, /watch, /saved или /digest заново.")
            val action = parts[2]; val argument = parts.getOrNull(3).orEmpty()
            when (action) {
                "cancel" -> return transition(null, "Диалог отменён")
                "mode" -> {
                    require(state.kind == "find" && state.step == "mode" && argument in setOf("telegram", "archive"))
                    return sourceMenu(state.copy(mode = argument, step = "sources"))
                }
                "source", "all" -> {
                    require(state.step == "sources" && state.kind in setOf("find", "watch"))
                    val available = repo.channels().map { it.id }
                    val selected = if (action == "all") available else {
                        val id = argument.toLongOrNull() ?: throw IllegalArgumentException(); require(id in available)
                        if (state.kind == "watch") if (id in state.sources) state.sources - id else state.sources + id else listOf(id)
                    }
                    require(selected.size in 0..10)
                    state = state.copy(sources = selected)
                    if (state.kind == "watch") return sourceMenu(state)
                    require(selected.isNotEmpty())
                    state = state.copy(step = "cards")
                    return load(botId, user, state)
                }
                "preview" -> { require(state.kind == "watch" && state.step == "sources"); return preview(state) }
                "sources" -> { require(state.kind == "watch" && state.step == "confirm"); return sourceMenu(state.copy(step = "sources")) }
                "confirm" -> {
                    require(state.kind == "watch" && state.step == "confirm")
                    val template = watchTemplate(state)
                    return WorkspaceTransition(null, BotPayload("Подписка «${state.name}» создана. /subscriptions — управление. Она также доступна в React."), template = template)
                }
                "watch" -> { require(state.kind == "find"); return watchStart(fresh("watch").copy(query = state.query, sources = state.sources)) }
                "period" -> { require(state.kind == "digest"); return digest(state, botId, user, argument) }
                "digestMore" -> { require(state.kind == "digest"); return digest(state, botId, user, state.mode, argument.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException()) }
                "savedMore" -> { require(state.kind == "saved" && state.cursor.isNotEmpty()); return saved(botId, user, state, state.cursor.toLong()) }
                "more" -> { require(state.kind == "find" && state.step == "cards" && (state.cursor.isNotEmpty() || state.messages.isEmpty())); return load(botId, user, state) }
                "next", "prev" -> {
                    require(state.kind in setOf("find", "saved"))
                    val index = state.index + if (action == "next") 1 else -1
                    require(index in state.messages.indices)
                    return card(botId, user, state.copy(index = index))
                }
                "save", "remove", "dismiss" -> {
                    require(state.kind in setOf("find", "saved"))
                    val message = state.messages.getOrNull(state.index) ?: throw IllegalArgumentException()
                    val status = when (action) { "save" -> "SAVED"; "dismiss" -> "REJECTED"; else -> "REMOVED" }
                    val updated = if (action == "dismiss" || (action == "remove" && state.kind == "saved")) state.copy(messages = state.messages.filterIndexed { i, _ -> i != state.index }, index = state.index.coerceAtMost((state.messages.size - 2).coerceAtLeast(0))) else state
                    return card(botId, user, updated).copy(decision = WorkspaceDecision(message, status))
                }
            }
            return transition(state, "Кнопка недоступна. Начните новый диалог.")
        } catch (_: TimeoutCancellationException) { return transition(state, "Поиск не успел завершиться. Повторите позже; курсор не изменён.", state?.let { listOf(listOf(button(it, "Повторить", "more"))) } ?: emptyList()) }
        catch (ex: CancellationException) { throw ex }
        catch (ex: TelegramRequestException) {
            val rate = ex.code == 429 || ex.reason.startsWith("FLOOD_WAIT")
            val seconds = if (rate) Regex("(?:FLOOD_WAIT_|retry after )(\\d+)", RegexOption.IGNORE_CASE).find(ex.reason)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(1, 86400) ?: 60 else 5
            return transition(state?.copy(retryAt = now() + seconds * 1000), if (rate) "Telegram ограничил поиск. Повторите через $seconds сек." else "Источник временно недоступен. Повторите поиск позже.", state?.let { listOf(listOf(button(it, "Повторить", "more"))) } ?: emptyList())
        } catch (_: IllegalArgumentException) { return transition(state, "Проверьте ввод: название до 100 символов, до 20 слов/фраз через запятую (до 700 символов на список), 1–10 сохранённых источников. /cancel — отменить.") }
        catch (_: IllegalStateException) { return transition(state, "Telegram пока не готов. Повторите поиск после подключения.") }
        catch (_: Exception) { return transition(state, "Поиск временно недоступен. Начните /find заново позже.") }
    }
    fun apply(botId: Long, user: Long, transition: WorkspaceTransition, subscriptions: SubscriptionService): BotPayload {
        transition.decision?.let { recruiter.decide(botId, user, it.message, it.status) }
        transition.template?.let { subscriptions.save(it) }
        repo.saveWorkspace(botId, user, transition.state, now())
        // Re-render after the decision so the Save/Remove label reflects committed state.
        return if (transition.decision != null && transition.state != null) card(botId, user, transition.state).payload else transition.payload
    }
}
