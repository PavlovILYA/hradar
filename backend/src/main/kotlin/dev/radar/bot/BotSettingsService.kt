package dev.radar.bot

import dev.radar.desktop.RuntimePaths
import dev.radar.research.Repositories
import dev.radar.research.SubscriptionService
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

@Serializable private data class StoredBot(val token: String, val identity: BotIdentity, val enabled: Boolean)
@Serializable data class BotSettingsStatus(val status: BotStatus, val configured: Boolean, val enabled: Boolean,
    val identity: BotIdentity? = null, val owner: BotAccessUser? = null, val users: List<BotAccessUser> = emptyList(),
    val invitations: List<BotInvitation> = emptyList(), val legacy: Boolean = false)
@Serializable data class BotInvitationLink(val id: String, val url: String, val expiresAt: Long)

/** A single process owns the active BotService. Saved settings always outrank legacy env values. */
class BotSettingsService(
    private val paths: RuntimePaths, private val repo: Repositories, private val subscriptions: SubscriptionService,
    private val owner: suspend () -> BotAccessUser, private val lookup: suspend (String) -> BotAccessUser,
    private val statusText: () -> String, private val link: suspend (Long, Long) -> String? = { _, _ -> null },
    private val gatewayFactory: (String) -> BotGateway = ::HttpsBotGateway,
    private val now: () -> Long = System::currentTimeMillis,
    private val workspace: BotWorkspaceService? = null,
    private val legacyConfig: BotConfig = BotConfig.fromEnvironment(),
) : AutoCloseable {
    private val mutex = Mutex()
    private var stored: StoredBot? = readStored()
    @Volatile private var bot: BotService = makeService()
    private var running = false

    private fun readStored(): StoredBot? {
        if (!Files.exists(paths.botConfiguration)) return null
        check(!Files.isSymbolicLink(paths.botConfiguration)) { "Файл настройки бота недоступен" }
        Files.setPosixFilePermissions(paths.botConfiguration, PosixFilePermissions.fromString("rw-------"))
        return try {
            Json.decodeFromString<StoredBot>(Files.readString(paths.botConfiguration)).also {
                check(it.identity.id == it.token.substringBefore(':').toLongOrNull() && it.identity.username.matches(Regex("[A-Za-z0-9_]{5,32}")))
            }
        }
        catch (_: Exception) { throw IllegalStateException("Файл настройки бота повреждён") }
    }
    private fun save(value: StoredBot) {
        val directory = paths.botConfiguration.parent
        Files.createDirectories(directory)
        check(!Files.isSymbolicLink(directory)) { "Каталог настройки бота недоступен" }
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val temp = Files.createTempFile(directory, "bot-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.writeString(temp, Json.encodeToString(value))
            Files.move(temp, paths.botConfiguration, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
    private fun makeService(): BotService {
        val saved = stored
        val config = if (saved == null) legacyConfig else BotConfig.managed(saved.token,
            repo.botAccessUsers(saved.identity.id).map { it.id }.toSet(), saved.enabled)
        return BotService(config, repo, subscriptions, statusText, link, now, gatewayFactory, workspace)
    }
    fun start() { running = true; bot.start() }
    fun status(): BotSettingsStatus {
        val saved = stored
        val id = saved?.identity?.id
        val users = id?.let(repo::botAccessUsers).orEmpty()
        val base = bot.status()
        val operational = if (id != null && saved?.enabled == false) base.copy(allowedUsers = users.size,
            registeredUsers = repo.botRegisteredCount(id), delivery = repo.botDeliveryCounts(id)) else base
        return BotSettingsStatus(operational, saved != null, saved?.enabled ?: legacyConfig.enabled,
            saved?.identity, users.find { it.role == "owner" }, users.filter { it.role == "guest" },
            id?.let { repo.botInvitations(it, now()) }.orEmpty(), saved == null && legacyConfig.enabled)
    }
    private suspend fun verified(token: String): BotIdentity {
        require(token.matches(Regex("[1-9][0-9]*:[A-Za-z0-9_-]+")) && token.length <= 256) { "Некорректный токен бота" }
        val api = gatewayFactory(token)
        return try {
            val identity = api.identity()
            require(identity.id == token.substringBefore(':').toLong() && identity.username.matches(Regex("[A-Za-z0-9_]{5,32}"))) { "Telegram вернул неожиданные данные бота" }
            identity
        } finally { api.close() }
    }
    suspend fun checkToken(token: String): BotIdentity = verified(token)
    suspend fun connect(token: String): BotSettingsStatus = mutex.withLock {
        val identity = verified(token)
        val enabled = stored?.enabled ?: false
        val account = if (enabled) owner() else null
        if (stored == null && legacyConfig.enabled && identity.id == legacyConfig.botId) {
            legacyConfig.allowedUsers.forEach { id ->
                repo.saveBotAccess(identity.id, id, "guest", "Ранее разрешённый пользователь", null)
            }
        }
        if (account != null) repo.replaceBotOwner(identity.id, account.id, account.name, account.username)
        val replacement = StoredBot(token, identity, enabled)
        save(replacement)
        stored = replacement
        switch()
        status()
    }
    suspend fun enable(enabled: Boolean): BotSettingsStatus = mutex.withLock {
        val current = stored ?: throw IllegalStateException("Сначала подключите бота")
        if (enabled) {
            val account = owner()
            require(account.id > 0) { "Завершите вход в Telegram в интерфейсе hradar" }
            repo.replaceBotOwner(current.identity.id, account.id, account.name, account.username)
        }
        val replacement = current.copy(enabled = enabled)
        save(replacement); stored = replacement; switch(); status()
    }
    private fun switch() {
        bot.close() // Cancels the old getUpdates request before another worker can start.
        bot = makeService()
        if (running) bot.start()
    }
    suspend fun createInvitation(): BotInvitationLink = mutex.withLock {
        val current = stored?.takeIf { it.enabled } ?: throw IllegalStateException("Сначала включите бота")
        val token = BotInvitationToken.create()
        val expires = now() + 600_000
        val id = UUID.randomUUID().toString()
        repo.createBotInvitation(id, current.identity.id, BotInvitationToken.hash(token)!!, expires)
        BotInvitationLink(id, "https://t.me/${current.identity.username}?start=$token", expires)
    }
    suspend fun recallInvitation(id: String): BotSettingsStatus = mutex.withLock {
        val botId = stored?.identity?.id ?: throw IllegalStateException("Бот не подключён")
        require(repo.recallBotInvitation(botId, id, now())) { "Приглашение уже недействительно" }
        status()
    }
    suspend fun resolveUser(username: String): BotAccessUser {
        val normalized = username.trim().removePrefix("@")
        require(normalized.matches(Regex("[A-Za-z0-9_]{5,32}"))) { "Введите корректный @username" }
        return try { lookup(normalized) }
        catch (ex: TelegramRequestException) {
            if (ex.code == 404 || ex.reason in setOf("USERNAME_NOT_OCCUPIED", "USERNAME_INVALID", "CHAT_NOT_FOUND"))
                throw IllegalArgumentException("Пользователь не найден. Отправьте приглашение по ссылке")
            throw ex
        }
    }
    suspend fun addUser(username: String, expectedId: Long): BotSettingsStatus = mutex.withLock {
        val current = stored?.takeIf { it.enabled } ?: throw IllegalStateException("Сначала включите бота")
        val user = resolveUser(username)
        require(user.id == expectedId && user.id > 0) { "Пользователь изменился. Найдите его ещё раз" }
        require(repo.botAccessUsers(current.identity.id).size < 100) { "Достигнут предел пользователей" }
        require(repo.botAccessUsers(current.identity.id).none { it.id == user.id && it.role == "owner" }) { "Основной аккаунт уже подключён" }
        repo.saveBotAccess(current.identity.id, user.id, "guest", user.name, user.username)
        repo.configureBot(current.identity.id, repo.botAccessUsers(current.identity.id).map { it.id }.toSet())
        bot.setAllowedUsers(repo.botAccessUsers(current.identity.id).map { it.id }.toSet())
        status()
    }
    suspend fun revokeUser(id: Long): BotSettingsStatus = mutex.withLock {
        val botId = stored?.identity?.id ?: throw IllegalStateException("Бот не подключён")
        require(repo.botAccessUsers(botId).any { it.id == id && it.role == "guest" }) { "Пользователь не найден" }
        val allowed = repo.botAccessUsers(botId).map { it.id }.toSet() - id
        bot.setAllowedUsers(allowed)
        repo.removeBotAccess(botId, id)
        repo.configureBot(botId, allowed)
        status()
    }
    override fun close() { bot.close() }
}
