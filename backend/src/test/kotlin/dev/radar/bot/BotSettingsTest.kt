package dev.radar.bot

import dev.radar.research.Repositories
import dev.radar.research.MatchingService
import dev.radar.research.SubscriptionService
import dev.radar.desktop.RuntimePaths
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.Executors
import kotlin.test.*

class BotSettingsTest {
    @Test fun getMeResponseCarriesBotIdentityWithoutToken() {
        val response = decodeBotResponse(200, """{"ok":true,"result":{"id":123,"is_bot":true,"first_name":"Synthetic Bot","username":"synthetic_bot"}}""".toByteArray())
        assertEquals(BotIdentity(123, "synthetic_bot", "Synthetic Bot"), decodeBotIdentity(response))
    }
    private class FakeGateway(private val identity: BotIdentity) : BotGateway {
        var closed = false
        override suspend fun identity() = identity
        override suspend fun updates(offset: Long) = emptyList<BotUpdate>()
        override suspend fun send(chatId: Long, payload: BotPayload) = 1L
        override suspend fun answerCallback(id: String, text: String) {}
        override fun close() { closed = true }
    }
    @Test fun connectOwnerSwitchAndRestartPreserveSeparateBotData() = runBlocking {
        val home = Files.createTempDirectory("bot-settings")
        val paths = RuntimePaths(home); paths.prepare()
        Repositories(paths.database).use { repo ->
            val subscriptions = SubscriptionService(repo, MatchingService())
            val calls = mutableListOf<FakeGateway>()
            val factory: (String) -> BotGateway = { token -> FakeGateway(BotIdentity(token.substringBefore(':').toLong(), "fake_bot", "Fake Bot")).also(calls::add) }
            fun service() = BotSettingsService(paths, repo, subscriptions, { BotAccessUser(7, "Owner", role = "owner") },
                { BotAccessUser(8, "Guest", "guest_user") }, { "ready" }, gatewayFactory = factory, legacyConfig = BotConfig.fromEnvironment { null })
            service().use { settings ->
                settings.start()
                assertFalse(settings.status().configured)
                assertEquals(123, settings.checkToken("123:valid_synthetic").id)
                assertFailsWith<IllegalArgumentException> { settings.checkToken("bad") }
                settings.connect("123:valid_synthetic")
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.botConfiguration)))
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.botConfiguration.parent)))
                assertFalse(settings.status().enabled)
                settings.enable(true)
                assertEquals(7, settings.status().owner?.id)
                val invitation = settings.createInvitation()
                assertTrue(invitation.url.matches(Regex("https://t\\.me/fake_bot\\?start=i_[A-Za-z0-9_-]{43}")))
                assertFalse(Json.encodeToString(settings.status()).contains("start=i_"))
                assertFalse(Json.encodeToString(settings.status()).contains("123:valid_synthetic"))
                assertEquals(1, settings.status().invitations.size)
                settings.recallInvitation(invitation.id)
                assertTrue(settings.status().invitations.isEmpty())
                settings.addUser("guest_user", 8)
                assertEquals(8, settings.status().users.single().id)
                repo.registerBotRecipient(123, 8, 8, 1000)
                settings.connect("456:another_synthetic")
                assertEquals(456, settings.status().identity?.id)
                assertTrue(repo.botRegistered(123, 8))
                assertTrue(calls.dropLast(1).all { it.closed })
                assertFalse(Files.readString(paths.botConfiguration).contains("guest_user"))
            }
            service().use { settings ->
                assertEquals(456, settings.status().identity?.id)
                assertTrue(settings.status().enabled)
                assertEquals(7, settings.status().owner?.id)
                assertTrue(settings.status().users.isEmpty())
            }
        }
    }
    @Test fun revokeCancelsQueuedMessagesAndDoesNotRemoveOwner() = runBlocking {
        val paths = RuntimePaths(Files.createTempDirectory("bot-revoke")); paths.prepare()
        Repositories(paths.database).use { repo ->
            val settings = BotSettingsService(paths, repo, SubscriptionService(repo, MatchingService()),
                { BotAccessUser(7, "Owner", role = "owner") }, { BotAccessUser(8, "Guest", "guest_user") }, { "ready" },
                gatewayFactory = { FakeGateway(BotIdentity(123, "fake_bot")) }, legacyConfig = BotConfig.fromEnvironment { null })
            settings.use {
                settings.connect("123:synthetic"); settings.enable(true); settings.addUser("guest_user", 8)
                repo.registerBotRecipient(123, 8, 8, 1000)
                repo.enqueueBot(123, "test", 8, 8, BotPayload("private synthetic"))
                assertEquals(1, repo.botDeliveryCounts(123).pending)
                assertFailsWith<IllegalArgumentException> { settings.revokeUser(7) }
                settings.revokeUser(8)
                assertEquals(0, repo.botDeliveryCounts(123).pending)
                assertFalse(repo.botRegistered(123, 8))
                assertEquals(7, settings.status().owner?.id)
            }
        }
    }
    @Test fun legacyEnvToWebKeepsExistingRecipientsAndPendingQueue() = runBlocking {
        val paths = RuntimePaths(Files.createTempDirectory("bot-legacy")); paths.prepare()
        Repositories(paths.database).use { repo ->
            val legacy = BotConfig.fromEnvironment { when (it) {
                "TG_BOT_TOKEN" -> "123:synthetic_legacy"; "TG_BOT_ALLOWED_USER_IDS" -> "7,8"; else -> null
            } }
            val settings = BotSettingsService(paths, repo, SubscriptionService(repo, MatchingService()),
                { BotAccessUser(7, "Owner", role = "owner") }, { throw IllegalArgumentException("Not needed") }, { "ready" },
                gatewayFactory = { FakeGateway(BotIdentity(123, "fake_bot")) }, legacyConfig = legacy)
            settings.use {
                repo.registerBotRecipient(123, 8, 8, 1000)
                repo.enqueueBot(123, "legacy-pending", 8, 8, BotPayload("synthetic"))
                settings.connect("123:synthetic_legacy")
                settings.enable(true)
                assertTrue(repo.botRegistered(123, 8))
                assertEquals(1, repo.botDeliveryCounts(123).pending)
                assertEquals(8, settings.status().users.single().id)
            }
        }
    }
    @Test fun invalidIdentityAndUnavailableOwnerNeverEnableEmptyAccess() = runBlocking {
        val paths = RuntimePaths(Files.createTempDirectory("bot-owner")); paths.prepare()
        Repositories(paths.database).use { repo ->
            val settings = BotSettingsService(paths, repo, SubscriptionService(repo, MatchingService()),
                { throw IllegalStateException("Завершите вход в Telegram") }, { throw IllegalArgumentException("Не найден") }, { "ready" },
                gatewayFactory = { FakeGateway(BotIdentity(999, "wrong_bot")) }, legacyConfig = BotConfig.fromEnvironment { null })
            settings.use {
                assertFailsWith<IllegalArgumentException> { settings.connect("123:synthetic") }
                assertFalse(settings.status().configured)
            }
            val valid = BotSettingsService(paths, repo, SubscriptionService(repo, MatchingService()),
                { throw IllegalStateException("Завершите вход в Telegram") }, { throw TelegramRequestException(404, "USERNAME_NOT_OCCUPIED") }, { "ready" },
                gatewayFactory = { FakeGateway(BotIdentity(123, "fake_bot")) }, legacyConfig = BotConfig.fromEnvironment { null })
            valid.use {
                valid.connect("123:synthetic")
                assertFailsWith<IllegalStateException> { valid.enable(true) }
                assertFalse(valid.status().enabled)
                assertTrue(repo.botAccessUsers(123).isEmpty())
                assertContains(assertFailsWith<IllegalArgumentException> { valid.resolveUser("missing_user") }.message.orEmpty(), "приглашение")
            }
        }
    }
    @Test fun invitationIsSingleUseAndPrivate() {
        Repositories(Files.createTempDirectory("bot-invite").resolve("db.sqlite")).use { repo ->
            repo.configureBot(123, setOf(7))
            val hash = MessageDigest.getInstance("SHA-256").digest("secret".toByteArray()).joinToString("") { "%02x".format(it) }
            repo.createBotInvitation("invite-1", 123, hash, 1100)
            assertFalse(repo.acceptBotInvitation(123, 1, hash, 8, 99, "Guest", null, 1000))
            assertTrue(repo.acceptBotInvitation(123, 2, hash, 8, 8, "Guest", null, 1000))
            assertFalse(repo.acceptBotInvitation(123, 3, hash, 9, 9, "Other", null, 1000))
            repo.createBotInvitation("expired", 123, "expired-hash", 999)
            assertFalse(repo.acceptBotInvitation(123, 4, "expired-hash", 9, 9, "Other", null, 1000))
            repo.createBotInvitation("recalled", 123, "recalled-hash", 1100)
            assertTrue(repo.recallBotInvitation(123, "recalled", 1000))
            assertFalse(repo.acceptBotInvitation(123, 5, "recalled-hash", 9, 9, "Other", null, 1000))
            assertEquals(listOf(8L), repo.botAccessUsers(123).map { it.id })
            assertTrue(repo.botRegistered(123, 8))
        }
    }

    @Test fun concurrentInvitationCanOnlyBeUsedOnce() {
        Repositories(Files.createTempDirectory("bot-invite-race").resolve("db.sqlite")).use { repo ->
            repo.configureBot(123, setOf(7))
            repo.createBotInvitation("invite-2", 123, "hash", 1100)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results = (8L..9L).map { id -> executor.submit<Boolean> { repo.acceptBotInvitation(123, id, "hash", id, id, "Guest", null, 1000) } }
                assertEquals(1, results.count { it.get() })
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun handlerOnlyGrantsPrivateValidInvitationsAndBlocksOtherCommands() = runBlocking {
        Repositories(Files.createTempDirectory("bot-handler-invite").resolve("db.sqlite")).use { repo ->
            val config = BotConfig.fromEnvironment { if (it == "TG_BOT_TOKEN") "123:synthetic" else "7" }
            repo.configureBot(123, setOf(7))
            val handler = BotUpdateHandler(config, repo, SubscriptionService(repo, MatchingService()), { "ready" }, { 1000 })
            val token = BotInvitationToken.create()
            repo.createBotInvitation("one", 123, BotInvitationToken.hash(token)!!, 1100)
            fun update(id: Long, chat: BotChat, text: String) = BotUpdate(id, BotMessage(8, chat, text, firstName = "Guest"))
            handler.handle(update(1, BotChat(-100, "supergroup"), "/start $token"), "fake_bot")
            assertTrue(repo.botAccessUsers(123).isEmpty())
            handler.handle(update(2, BotChat(8, "private"), "/status"), "fake_bot")
            assertFalse(repo.botRegistered(123, 8))
            handler.handle(update(3, BotChat(8, "private"), "/start $token"), "fake_bot")
            assertTrue(repo.botRegistered(123, 8))
            assertEquals(1, repo.botDeliveryCounts(123).pending)
            handler.handle(update(4, BotChat(9, "private"), "/start $token").copy(message = BotMessage(9, BotChat(9, "private"), "/start $token")), "fake_bot")
            assertFalse(repo.botRegistered(123, 9))
        }
    }
    @Test fun failedInvitationMigrationRollsBackWithoutLosingArchive() {
        val path = Files.createTempDirectory("bot-v9-rollback").resolve("db.sqlite")
        Repositories(path).use { repo -> repo.saveBotAccess(123, 7, "owner", "Owner", null) }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            s.execute("DROP TABLE telegram_sources"); s.execute("DROP TABLE bot_access"); s.execute("DROP TABLE bot_invitations")
            s.execute("PRAGMA user_version=8"); s.execute("CREATE VIEW bot_access AS SELECT id FROM bot_state")
        } }
        assertFails { Repositories(path).close() }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            assertEquals(8, s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) })
            assertEquals(0, s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='bot_invitations'").use { it.next(); it.getInt(1) })
            s.execute("DROP VIEW bot_access")
        } }
        Repositories(path).use { repo -> assertTrue(repo.botAccessUsers(123).isEmpty()) }
    }
}
