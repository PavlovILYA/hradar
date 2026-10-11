package dev.radar.bot

import dev.radar.research.*

/** Only explicit operator commands; free-text and publication content are never executable. */
class BotUpdateHandler(
    private val config: BotConfig, private val repo: Repositories, private val subscriptions: SubscriptionService,
    private val statusText: () -> String, private val now: () -> Long = System::currentTimeMillis,
    private val workspace: BotWorkspaceService? = null,
) {
    private val inbox = BotInboxService(repo)
    suspend fun handle(update: BotUpdate, botUsername: String): String? {
        val botId = config.botId ?: return null
        var callbackId: String? = null
        if (update.id < repo.botOffset(botId)) return null
        fun ignore(): String? { repo.handleBotUpdate(botId, update.id) {}; return null }
        val sender = update.callback?.userId ?: update.message?.userId ?: return ignore()
        val chatInfo = update.callback?.chat ?: update.message?.chat ?: return ignore()
        if ((update.callback?.isBot ?: update.message?.isBot ?: true) || sender !in config.allowedUsers || chatInfo.type != "private" || chatInfo.id != sender) return ignore()
        val rawInput = update.message?.text?.trim()
        val commandTarget = rawInput?.substringBefore(' ')?.substringBefore('\n')?.substringAfter('@', "").orEmpty()
        if (rawInput?.startsWith('/') == true && commandTarget.isNotEmpty() && !commandTarget.equals(botUsername, true)) return ignore()
        val prepared = if (repo.botRegistered(botId, sender)) workspace?.prepare(botId, sender, rawInput, update.callback) else null
        repo.handleBotUpdate(botId, update.id) {
            val user = update.callback?.userId ?: update.message?.userId ?: return@handleBotUpdate
            val chat = update.callback?.chat ?: update.message?.chat ?: return@handleBotUpdate
            val isBot = update.callback?.isBot ?: update.message?.isBot ?: true
            if (isBot || user !in config.allowedUsers || chat.type != "private" || chat.id != user) return@handleBotUpdate
            fun reply(payload: BotPayload) = repo.enqueueBot(botId, "update:${update.id}", user, chat.id, payload)
            if (prepared != null && workspace != null) {
                val result = try { workspace.apply(botId, user, prepared, subscriptions) } catch (_: IllegalArgumentException) { BotPayload("Источник или условия изменились. Начните /find или /watch заново.") }
                reply(result.copy(editMessageId = update.callback?.messageId))
                callbackId = update.callback?.id
                return@handleBotUpdate
            }
            val callback = update.callback
            if (callback != null) {
                if (!repo.botRegistered(botId, user)) return@handleBotUpdate
                callbackId = callback.id
                val parts = callback.data.orEmpty().split(':')
                if (parts.size == 2 && parts[0] in setOf("matches", "sources")) {
                    val cursor = parts[1].toLongOrNull() ?: return@handleBotUpdate
                    if (parts[0] == "matches" && cursor > 0) reply(inbox.matches(cursor, botId, user).copy(editMessageId = callback.messageId))
                    if (parts[0] == "sources" && cursor in 0..10000) reply(inbox.sources(cursor.toInt()).copy(editMessageId = callback.messageId))
                } else if (parts.size == 2 && parts[0] in setOf("match", "read", "review", "publish", "reject") && parts[1].matches(Regex("[a-f0-9-]{36}"))) {
                    if (parts[0] in setOf("match", "read", "review")) reply(inbox.details(parts[1], parts[0] == "read", botId, user).copy(editMessageId = callback.messageId.takeUnless { parts[0] == "review" }))
                    else reply(BotPayload(if (repo.confirmBotPublish(botId, user, parts[1], parts[0] == "publish", now())) "Публикация подтверждена и поставлена в очередь" else "Публикация отменена или разрешение истекло"))
                } else if (parts.size == 2 && parts[0] in setOf("pause", "resume") && parts[1].matches(Regex("[a-f0-9-]{36}"))) {
                    val template = subscriptions.list().find { it.id == parts[1] && it.subscribed && it.sourceMode != "global" }
                    if (template == null) reply(BotPayload("Подписка больше недоступна"))
                    else {
                        try {
                            val saved = subscriptions.setActive(template.id, parts[0] == "resume")
                            reply(BotPayload("${saved.name}: ${if (saved.active) "мониторинг включён" else "мониторинг приостановлен"}"))
                        } catch (_: IllegalArgumentException) { reply(BotPayload("Подписка больше недоступна; проверьте её источники в hradar")) }
                    }
                } else if (parts.size == 3 && parts[0] == "list" && parts[1] in setOf("pause", "resume", "subscriptions")) {
                    val offset = parts[2].toIntOrNull()?.takeIf { it in 0..10_000 } ?: return@handleBotUpdate
                    reply(subscriptionMenu(parts[1], offset))
                }
                return@handleBotUpdate
            }
            val raw = update.message?.text?.trim()?.take(256) ?: return@handleBotUpdate
            val addressedCommand = raw.substringBefore(' ').substringBefore('\n')
            val target = addressedCommand.substringAfter('@', "")
            if (target.isNotEmpty() && !target.equals(botUsername, ignoreCase = true)) return@handleBotUpdate
            val command = addressedCommand.substringBefore('@')
            if (command == "/start") {
                repo.registerBotRecipient(botId, user, chat.id, now())
                reply(BotPayload("hradar подключён. Ваш user ID: $user. Уведомления поступают только о новых совпадениях после регистрации.\n/help · /find · /watch · /saved · /matches · /sources · /digest · /status · /subscriptions · /pause · /resume"))
            } else if (command in setOf("/status", "/subscriptions", "/pause", "/resume", "/matches", "/sources", "/digest", "/help", "/agent_publish")) {
                if (!repo.botRegistered(botId, user)) {
                    // /start is the only opt-in operation; no implicit recipient registration.
                    return@handleBotUpdate
                }
                when (command) {
                    "/matches" -> reply(inbox.matches(botId = botId, user = user))
                    "/sources" -> reply(inbox.sources())
                    "/digest" -> reply(inbox.digest())
                    "/help" -> reply(BotPayload("""/find запрос — поиск в выбранных источниках Telegram или архиве
/watch — создание подписки с телефона
/saved — избранное
/cancel — отменить диалог
/matches — сохранённые совпадения, кнопки просмотра и отметки прочитанного
/sources — сохранённые источники
/digest — совпадения сегодня или за 7 дней
/status — состояние
/subscriptions · /pause · /resume — общие подписки
/agent_publish on — отдельно разрешить запросы публикации от агента
/agent_publish off — отозвать разрешение
Каждая публикация требует вашего подтверждения. Прочитанность общая с hradar. Свободный текст не выполняется."""))
                    "/agent_publish" -> {
                        val mode = raw.substringAfter(' ', "").trim()
                        if (mode in setOf("on", "off")) {
                            repo.allowBotPublish(botId, user, mode == "on")
                            reply(BotPayload(if (mode == "on") "Запросы агента разрешены. Каждый набор требует отдельного подтверждения в этом чате." else "Разрешение отозвано; ожидающие публикации отменены."))
                        } else reply(BotPayload("/agent_publish on или /agent_publish off"))
                    }
                    "/status" -> reply(BotPayload(statusText().take(2000)))
                    else -> reply(subscriptionMenu(command.removePrefix("/"), 0))
                }
            }
        }
        return callbackId
    }
    private fun subscriptionMenu(mode: String, offset: Int): BotPayload {
        val rules = subscriptions.list().filter { it.subscribed && it.sourceMode != "global" && when (mode) { "pause" -> it.active; "resume" -> !it.active; else -> true } }
        val page = rules.drop(offset).take(20)
        val rows = page.map { t -> listOf(BotButton("${if (t.active) "⏸" else "▶"} ${t.name.take(60)}", "${if (mode == "subscriptions") { if (t.active) "pause" else "resume" } else mode}:${t.id}")) }.toMutableList()
        val navigation = buildList {
            if (offset > 0) add(BotButton("← Назад", "list:$mode:${(offset - 20).coerceAtLeast(0)}"))
            if (offset + 20 < rules.size) add(BotButton("Далее →", "list:$mode:${offset + 20}"))
        }
        if (navigation.isNotEmpty()) rows.add(navigation)
        return BotPayload(if (rules.isEmpty()) "Нет подходящих подписок. Настройте мониторинг в hradar." else "${when (mode) { "pause" -> "Выберите подписку для остановки"; "resume" -> "Выберите подписку для возобновления"; else -> "Подписки: кнопка переключает мониторинг" }} (${offset + 1}–${offset + page.size} из ${rules.size})\nИзменения общие для всех операторов и интерфейса hradar.", rows)
    }
}
