package dev.radar.bot

import dev.radar.research.*
import dev.radar.telegram.TelegramMessage
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.routing
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

private class FakeBot : BotGateway {
    var updates = emptyList<BotUpdate>()
    val offsets = mutableListOf<Long>()
    val sent = mutableListOf<Pair<Long, BotPayload>>()
    val callbacks = mutableListOf<String>()
    var sendError: BotApiException? = null
    var pollError: BotApiException? = null
    var answerError: BotApiException? = null
    var beforeSend: suspend () -> Unit = {}
    var identityCalls = 0
    var closed = false
    override suspend fun identity(): BotIdentity { identityCalls++; return BotIdentity(123, "synthetic_bot") }
    override suspend fun updates(offset: Long): List<BotUpdate> {
        offsets.add(offset)
        pollError?.let { pollError = null; throw it }
        return updates
    }
    override suspend fun send(chatId: Long, payload: BotPayload): Long {
        beforeSend()
        sendError?.let { sendError = null; throw it }
        sent.add(chatId to payload); return sent.size.toLong()
    }
    override suspend fun answerCallback(id: String, text: String) { answerError?.let { answerError = null; throw it }; callbacks.add(id) }
    override fun close() { closed = true }
}

class BotTest {
    private fun config(token: String? = "123:synthetic_not_a_real_token", allowed: String? = "7") = BotConfig.fromEnvironment { when (it) { "TG_BOT_TOKEN" -> token; "TG_BOT_ALLOWED_USER_IDS" -> allowed; else -> null } }
    private fun database() = Files.createTempDirectory("hradar-bot-test").resolve("research.sqlite")
    private val channel = MonitoredChannel(-1001, "synthetic_channel", "Synthetic source")
    private fun messageUpdate(id: Long, text: String, user: Long = 7, chat: BotChat = BotChat(user, "private")) = BotUpdate(id, message = BotMessage(user, chat, text))
    private fun callback(id: Long, data: String, user: Long = 7, chat: BotChat = BotChat(user, "private")) = BotUpdate(id, callback = BotCallback("callback-$id", user, chat, data))
    private inner class Fixture(path: Path = database(), cfg: BotConfig = config()) : AutoCloseable {
        val repo = Repositories(path)
        val fake = FakeBot()
        var time = System.currentTimeMillis()
        var returnedLink: String? = "https://t.me/synthetic_channel/17"
        var linkCalls = 0
        val matching = MatchingService()
        val subscriptions = SubscriptionService(repo, matching)
        val notifications = NotificationService(repo, matching)
        val bot = BotService(cfg, repo, subscriptions, { "TDLib: Ready\nАктивные подписки: ${subscriptions.list().count { it.active && it.subscribed }}" }, link = { _, _ -> linkCalls++; returnedLink }, now = { time }, gatewayFactory = { fake })
        fun rule() = repo.saveTemplate(SearchTemplate(name = "Senior Java", required = listOf("Java"), excluded = listOf("Junior"), telegramQuery = "Java", subscribed = true))
        fun publication(id: Long = 1, text: String = "Synthetic Java position", date: Int = (time / 1000).toInt() + 1) = ResearchMessage(id, channel.id, date, text, channel.title, channel.username)
        suspend fun optIn() {
            fake.updates = listOf(messageUpdate(1, "/start")); bot.pollOnce(); bot.deliverOnce()
            fake.updates = emptyList(); fake.sent.clear(); time += 2000
        }
        override fun close() { bot.close(); repo.close() }
    }
    @Test fun inboxCommandsNavigationReadAndOriginalUseSavedMatches() = runBlocking {
        Fixture().use { f ->
            f.repo.saveChannel(channel); val rule = f.rule()
            repeat(12) { f.repo.recordMatch(rule, f.publication(it.toLong() + 1)) }
            f.optIn()
            val inbox = BotInboxService(f.repo)
            val first = inbox.matches()
            assertEquals(11, first.buttons.size)
            val recent = f.repo.recentMatches(12)
            val olderPage = inbox.matches(recent[9].sequence)
            assertEquals(3, olderPage.buttons.size)
            val selected = recent.first()
            assertContains(inbox.details(selected.id).text, "Не прочитано")
            assertContains(inbox.details(selected.id, true).text, "Прочитано")
            assertEquals(11, f.repo.unreadCount())
            assertContains(inbox.sources().text, channel.username)
            assertContains(inbox.digest().text, "11")
            val handler = BotUpdateHandler(config(), f.repo, f.subscriptions, { "ready" }, { f.time })
            handler.handle(messageUpdate(2, "/help@synthetic_bot"), "synthetic_bot")
            handler.handle(callback(3, "read:${selected.id}").let { it.copy(callback = it.callback!!.copy(messageId = 17)) }, "synthetic_bot")
            handler.handle(callback(3, "read:${selected.id}").let { it.copy(callback = it.callback!!.copy(messageId = 17)) }, "synthetic_bot")
            assertEquals(2, f.bot.status().delivery.pending)
            f.bot.deliverOnce(); assertContains(f.fake.sent.single().second.text, "/matches")
            f.time += 2000; f.bot.deliverOnce()
            assertTrue(f.fake.sent.last().second.buttons.flatten().any { it.url == f.returnedLink })
            assertEquals(1, f.linkCalls)
            assertEquals(17L, f.fake.sent.last().second.editMessageId)
        }
    }
    @Test fun agentPublishRequiresSeparatePermissionAndRecipientConfirmationWithDurableReplayProtection() = runBlocking {
        val path = database(); var request = ""; var matchId = ""
        Fixture(path).use { f ->
            val rule = f.rule(); f.repo.recordMatch(rule, f.publication()); f.optIn()
            f.repo.setMcpEnabled(true); f.repo.allowMcpSource(channel)
            matchId = f.repo.recentMatches(1).single().id
            assertFailsWith<IllegalArgumentException> { f.repo.requestBotPublish(7, listOf(matchId), f.time) }
            val handler = BotUpdateHandler(config(), f.repo, f.subscriptions, { "ready" }, { f.time })
            handler.handle(messageUpdate(2, "/agent_publish on"), "synthetic_bot")
            assertTrue(f.repo.botPublishPermitted(7))
            assertFailsWith<IllegalArgumentException> { f.repo.requestBotPublish(8, listOf(matchId), f.time) }
            request = f.repo.requestBotPublish(7, listOf(matchId), f.time)
            assertEquals(2, f.bot.status().delivery.pending) // permission reply and metadata-only preview
            f.bot.deliverOnce(); f.time += 2000; f.bot.deliverOnce()
            assertContains(f.fake.sent.last().second.text, "Подтверждение")
            handler.handle(callback(3, "publish:$request", 8), "synthetic_bot")
            assertEquals(0, f.bot.status().delivery.pending)
        }
        Fixture(path).use { f ->
            val handler = BotUpdateHandler(config(), f.repo, f.subscriptions, { "ready" }, { f.time })
            handler.handle(callback(4, "publish:$request"), "synthetic_bot")
            assertEquals(2, f.bot.status().delivery.pending)
            handler.handle(callback(4, "publish:$request"), "synthetic_bot")
            assertEquals(2, f.bot.status().delivery.pending)
            assertFalse(f.repo.confirmBotPublish(123, 7, request, true, f.time))
            f.time += 10_000; f.bot.deliverOnce(); assertContains(f.fake.sent.single().second.text, "Synthetic Java")
            val next = f.repo.requestBotPublish(7, listOf(matchId), f.time)
            f.repo.revokeMcpSource(channel.id); f.repo.allowMcpSource(channel)
            assertFalse(f.repo.confirmBotPublish(123, 7, next, true, f.time))
        }
    }
    @Test fun publishExpirationRejectionDisableAndPermissionRevocationCancelRequestsAndResults() = runBlocking {
        Fixture().use { f ->
            val rule = f.rule(); f.repo.recordMatch(rule, f.publication()); f.optIn()
            f.repo.setMcpEnabled(true); f.repo.allowMcpSource(channel); f.repo.allowBotPublish(123, 7, true)
            val ids = listOf(f.repo.recentMatches(1).single().id)
            val expired = f.repo.requestBotPublish(7, ids, f.time)
            assertFalse(f.repo.confirmBotPublish(123, 7, expired, true, f.time + 600_001))
            val rejected = f.repo.requestBotPublish(7, ids, f.time + 600_002)
            assertFalse(f.repo.confirmBotPublish(123, 7, rejected, false, f.time + 600_003))
            assertFalse(f.repo.confirmBotPublish(123, 7, rejected, true, f.time + 600_004))
            val confirmed = f.repo.requestBotPublish(7, ids, f.time + 600_005)
            assertTrue(f.repo.confirmBotPublish(123, 7, confirmed, true, f.time + 600_006))
            f.repo.allowBotPublish(123, 7, false)
            assertEquals(0, f.bot.status().delivery.pending)
            f.repo.allowBotPublish(123, 7, true)
            val disabled = f.repo.requestBotPublish(7, ids, f.time + 600_007)
            f.repo.setMcpEnabled(false); f.repo.setMcpEnabled(true)
            assertFalse(f.repo.confirmBotPublish(123, 7, disabled, true, f.time + 600_008))
            f.bot.deliverOnce(); assertTrue(f.fake.sent.isEmpty())
        }
    }
    @Test fun confirmedPublicationAndOutboxRollbackTogetherAndExpiredPreviewNeverSends() = runBlocking {
        val path = database()
        Fixture(path).use { f ->
            val rule = f.rule(); f.repo.recordMatch(rule, f.publication()); f.optIn()
            f.repo.setMcpEnabled(true); f.repo.allowMcpSource(channel); f.repo.allowBotPublish(123, 7, true)
            val request = f.repo.requestBotPublish(7, listOf(f.repo.recentMatches(1).single().id), f.time)
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
                s.execute("CREATE TRIGGER reject_publish BEFORE INSERT ON bot_outbox BEGIN SELECT RAISE(ABORT,'synthetic test'); END")
            } }
            assertFailsWith<java.sql.SQLException> { f.repo.confirmBotPublish(123, 7, request, true, f.time) }
            assertEquals(1, f.bot.status().delivery.pending)
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { it.execute("DROP TRIGGER reject_publish") } }
            assertTrue(f.repo.confirmBotPublish(123, 7, request, true, f.time))
            f.time += 600_001
            f.bot.deliverOnce() // confirmed request's now-stale preview is suppressed
            assertTrue(f.fake.sent.isEmpty())
            f.bot.deliverOnce() // human-confirmed result remains deliverable after expiry
            assertContains(f.fake.sent.single().second.text, "Synthetic Java")
        }
    }
    @Test fun versionFiveMigrationPreservesRecipientsCursorAndQueuedPayloads() = runBlocking {
        val path = database()
        Fixture(path).use { f ->
            f.repo.saveChannel(channel); val rule = f.rule(); f.optIn()
            f.repo.recordMatch(rule, f.publication())
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            s.execute("DROP TABLE bot_publish_request"); s.execute("DROP TABLE bot_publish_permission"); s.execute("DROP TABLE bot_workspace"); s.execute("DROP TABLE publication_decisions"); s.execute("DROP INDEX matches_date"); s.execute("ALTER TABLE matched_messages DROP COLUMN message_date"); s.execute("PRAGMA user_version=5")
        } }
        Fixture(path).use { f ->
            assertEquals(2L, f.repo.botOffset(123)); assertTrue(f.repo.botRegistered(123, 7))
            assertEquals(1, f.bot.status().delivery.pending); assertEquals(1, f.repo.unreadCount())
            assertFalse(f.repo.botPublishPermitted(7)); assertEquals(channel, f.repo.channels().single())
            f.time += 10_000; f.bot.deliverOnce(); assertContains(f.fake.sent.single().second.text, "Synthetic Java")
        }
    }
    @Test fun disabledOrMissingInvalidAllowlistNeverStartsGateway() = runBlocking {
        for (cfg in listOf(config(null), config(allowed = null), config(allowed = ""), config(allowed = "7,invalid"), config(allowed = "-7"))) {
            Repositories(database()).use { repo ->
                BotService(cfg, repo, SubscriptionService(repo, MatchingService()), { "unused" }, gatewayFactory = { error("No network without valid config") }).use { bot ->
                    bot.start(); bot.pollOnce(); bot.deliverOnce()
                    assertTrue(bot.status().state in setOf("disabled", "blocked"))
                }
            }
        }
        assertFalse(config().toString().contains("synthetic_not_a_real_token"))
    }
    @Test fun allowedStartRegistersPrivateChatAndReplayDoesNotDuplicateReply() = runBlocking {
        Fixture().use { f ->
            f.fake.updates = listOf(messageUpdate(1, "/start"))
            f.bot.pollOnce(); f.bot.pollOnce()
            assertTrue(f.repo.botRegistered(123, 7)); assertEquals(2, f.repo.botOffset(123))
            assertEquals(listOf(0L, 2L), f.fake.offsets)
            assertEquals(1, f.bot.status().delivery.pending)
            f.bot.deliverOnce()
            assertEquals(7L, f.fake.sent.single().first)
            assertContains(f.fake.sent.single().second.text, "Ваш user ID: 7")
            assertEquals(1, f.bot.status().delivery.sent)
        }
    }
    @Test fun unauthorizedUsersGroupsSpoofedChatsAndCallbacksRevealNothing() = runBlocking {
        Fixture().use { f ->
            val t = f.rule()
            f.fake.updates = listOf(messageUpdate(1, "/start", 8), messageUpdate(2, "/start", 7, BotChat(-10, "group")), messageUpdate(3, "/start", 7, BotChat(8, "private")), messageUpdate(4, "/status", 8), callback(5, "pause:${t.id}", 8))
            f.bot.pollOnce(); f.bot.deliverOnce()
            assertEquals(0, f.bot.status().registeredUsers); assertEquals(0, f.bot.status().delivery.pending)
            assertTrue(f.fake.sent.isEmpty()); assertTrue(f.fake.callbacks.isEmpty()); assertTrue(f.repo.template(t.id)!!.active)
        }
    }
    @Test fun newMatchUsesExistingMatchingDedupAndOnlyActualLink() = runBlocking {
        Fixture().use { f ->
            f.rule(); f.optIn()
            assertEquals(0, f.notifications.process(channel, f.publication(text = "Java Junior")))
            assertEquals(1, f.notifications.process(channel, f.publication()))
            assertEquals(0, f.notifications.process(channel, f.publication()))
            assertEquals(1, f.repo.unreadCount()); assertEquals(1, f.bot.status().delivery.pending)
            f.bot.deliverOnce(); f.time += 2000; f.bot.deliverOnce()
            val result = f.fake.sent.single().second.text
            assertContains(result, "Senior Java"); assertContains(result, "Synthetic source")
            assertContains(result, "https://t.me/synthetic_channel/17"); assertEquals(1, f.linkCalls)
            assertEquals(0, f.bot.status().delivery.pending)
        }
        assertNull(verifiedTelegramLink("https://evil.invalid/publication"))
        assertNull(verifiedTelegramLink("https://t.me@evil.invalid/publication"))
        assertNull(verifiedTelegramLink("http://t.me/test/1"))
    }
    @Test fun firstOptInDoesNotBackfillOldMatchesAndOlderRecoveryPublications() = runBlocking {
        Fixture().use { f ->
            f.rule()
            f.notifications.process(channel, f.publication())
            assertEquals(0, f.bot.status().delivery.pending)
            f.optIn()
            f.time += 10_000
            f.notifications.process(channel, f.publication(2, date = (f.time / 1000).toInt() - 100))
            assertEquals(0, f.bot.status().delivery.pending)
            f.returnedLink = null
            f.notifications.process(channel, f.publication(3))
            f.bot.deliverOnce()
            assertFalse(f.fake.sent.single().second.text.contains("https://"))
        }
    }
    @Test fun networkAnd429RetriesAreDurableAndObserveTelegramWait() = runBlocking {
        val path = database()
        var resumeAt = 0L
        Fixture(path).use { f ->
            f.rule(); f.optIn(); f.notifications.process(channel, f.publication())
            f.fake.sendError = BotApiException()
            f.bot.deliverOnce(); assertTrue(f.fake.sent.isEmpty())
            f.bot.deliverOnce(); assertTrue(f.fake.sent.isEmpty())
            f.time += 2001; f.fake.sendError = BotApiException(429, 45)
            f.bot.deliverOnce()
            resumeAt = f.time + 45_000
            assertEquals(resumeAt, f.bot.status().retryAt)
            f.time += 44_999; f.bot.deliverOnce(); assertTrue(f.fake.sent.isEmpty())
        }
        Fixture(path).use { f ->
            f.time = resumeAt; f.bot.deliverOnce()
            assertEquals(1, f.fake.sent.size); assertEquals(0, f.bot.status().delivery.pending)
            assertEquals(1, f.repo.unreadCount())
            assertTrue(f.repo.botRegistered(123, 7)); assertEquals(2, f.repo.botOffset(123))
        }
    }
    @Test fun poll429PersistsOffsetAndCooldownAcrossRestart() = runBlocking {
        val path = database(); var retryAt = 0L
        Fixture(path).use { f ->
            f.optIn(); f.fake.pollError = BotApiException(429, 30); f.bot.pollOnce()
            retryAt = f.time + 30_000
            val calls = f.fake.offsets.size; f.bot.pollOnce(); assertEquals(calls, f.fake.offsets.size)
        }
        Fixture(path).use { f ->
            f.time = retryAt - 1; f.bot.pollOnce(); assertTrue(f.fake.offsets.isEmpty())
            f.time++; f.bot.pollOnce(); assertEquals(listOf(2L), f.fake.offsets)
        }
    }
    @Test fun inlinePauseResumeShareStateWithApplicationAndReplaysAreIdempotent() = runBlocking {
        val path = database(); var id = ""
        Fixture(path).use { f ->
            id = f.rule().id; f.optIn()
            f.fake.updates = listOf(messageUpdate(2, "/pause")); f.bot.pollOnce(); f.bot.deliverOnce()
            assertEquals("pause:$id", f.fake.sent.single().second.buttons.first().single().callback)
            f.fake.updates = listOf(callback(3, "pause:$id")); f.bot.pollOnce()
            assertFalse(f.subscriptions.list().single().active)
            assertEquals(0, f.notifications.process(channel, f.publication()))
            f.fake.updates = listOf(messageUpdate(4, "/resume")); f.bot.pollOnce()
            f.fake.updates = listOf(callback(5, "resume:$id")); f.bot.pollOnce()
            assertTrue(f.repo.template(id)!!.active)
            f.fake.updates = listOf(callback(3, "pause:$id")); f.bot.pollOnce()
            assertTrue(f.repo.template(id)!!.active)
            assertEquals(1, f.notifications.process(channel, f.publication()))
        }
        Fixture(path).use { f -> assertTrue(f.subscriptions.list().single().active); assertEquals(id, f.subscriptions.list().single().id) }
    }
    @Test fun removalFromAllowlistCancelsPendingDeliveryAndDifferentBotIsIsolated() = runBlocking {
        val path = database()
        Fixture(path).use { f -> f.rule(); f.optIn(); f.notifications.process(channel, f.publication()); assertEquals(1, f.bot.status().delivery.pending) }
        Fixture(path, config(allowed = "8")).use { f -> f.bot.deliverOnce(); assertTrue(f.fake.sent.isEmpty()); assertEquals(0, f.bot.status().delivery.pending); assertFalse(f.repo.botRegistered(123, 7)) }
        Fixture(path, config(token = "456:another_synthetic_token")).use { f -> assertEquals(0, f.bot.status().delivery.pending); assertEquals(0, f.bot.status().registeredUsers) }
    }
    @Test fun cancellationLeavesPendingEventForNextWorkerAndCloseIsIdempotent() = runBlocking {
        Fixture().use { f ->
            f.rule(); f.optIn(); f.notifications.process(channel, f.publication())
            val entered = CompletableDeferred<Unit>()
            f.fake.beforeSend = { entered.complete(Unit); awaitCancellation() }
            val task = launch { f.bot.deliverOnce() }
            entered.await(); task.cancelAndJoin()
            assertEquals(1, f.bot.status().delivery.pending)
            f.fake.beforeSend = {}; f.bot.deliverOnce(); assertEquals(1, f.fake.sent.size)
            f.bot.close(); f.bot.close(); assertTrue(f.fake.closed)
        }
    }
    @Test fun migrationFromV4PreservesOldDataWithoutCreatingHistoricalDeliveries() {
        val path = database()
        Repositories(path).use { repo ->
            repo.saveChannel(channel)
            val t = repo.saveTemplate(SearchTemplate(name = "Java", required = listOf("Java"), telegramQuery = "Java"))
            repo.recordMatch(t, ResearchMessage(1, channel.id, 1700000000, "Synthetic Java", channel.title, channel.username))
            repo.allowMcpSource(channel); repo.setMcpEnabled(true)
            val state = repo.beginHistorySync(channel)
            repo.saveHistoryPage(state, listOf(TelegramMessage(1, 1700000000, "Synthetic Java", channel.id)))
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            listOf("bot_publish_request", "bot_publish_permission", "bot_outbox", "bot_recipients", "bot_state").forEach { s.execute("DROP TABLE $it") }; s.execute("DROP TABLE bot_workspace"); s.execute("DROP TABLE publication_decisions"); s.execute("DROP INDEX matches_date"); s.execute("ALTER TABLE matched_messages DROP COLUMN message_date"); s.execute("PRAGMA user_version=4")
        } }
        Fixture(path).use { f ->
            assertEquals(channel, f.repo.channels().single()); assertEquals(1, f.repo.unreadCount())
            assertTrue(f.repo.mcpEnabled()); assertEquals(listOf(channel), f.repo.mcpSources())
            assertEquals(1, f.repo.historyMessages(channel.id, 10, null, "Java").total)
            assertEquals(0, f.bot.status().delivery.pending)
        }
    }
    @Test fun botApiResponseUsesRetryAfterAndNeverExposesDescription() {
        val error = assertFailsWith<BotApiException> {
            decodeBotResponse(429, """{"ok":false,"error_code":429,"description":"synthetic confidential detail","parameters":{"retry_after":17}}""".toByteArray())
        }
        assertEquals(429, error.code); assertEquals(17L, error.retryAfterSeconds)
        assertFalse(error.toString().contains("confidential")); assertNull(error.cause)
        assertFailsWith<BotApiException> { decodeBotResponse(502, "synthetic confidential detail".toByteArray()) }
        assertFailsWith<BotApiException> { decodeBotResponse(200, ByteArray(2 * 1024 * 1024 + 1)) }
    }
    @Test fun matchAndOutboxRollbackTogetherBeforeRetry() = runBlocking {
        // A failing queue write must not leave a match whose delivery event was lost.
        val path = database()
        Fixture(path).use { f ->
            f.rule(); f.optIn()
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
                s.execute("CREATE TRIGGER reject_queue BEFORE INSERT ON bot_outbox BEGIN SELECT RAISE(ABORT,'synthetic test'); END")
            } }
            assertFailsWith<java.sql.SQLException> { f.notifications.process(channel, f.publication()) }
            assertEquals(0, f.repo.unreadCount()); assertEquals(0, f.bot.status().delivery.pending)
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { it.execute("DROP TRIGGER reject_queue") } }
            assertEquals(1, f.notifications.process(channel, f.publication()))
            assertEquals(1, f.bot.status().delivery.pending)
        }
    }
    @Test fun callback429DefersAllRequestsButKeepsCommittedCommandAndReply() = runBlocking {
        Fixture().use { f ->
            val rule = f.rule(); f.optIn()
            f.fake.answerError = BotApiException(429, 25)
            f.fake.updates = listOf(callback(2, "pause:${rule.id}"), callback(3, "resume:${rule.id}"))
            f.bot.pollOnce()
            assertTrue(f.repo.template(rule.id)!!.active)
            assertEquals(4L, f.repo.botOffset(123))
            assertEquals(2, f.bot.status().delivery.pending)
            f.bot.deliverOnce(); assertTrue(f.fake.sent.isEmpty()); assertTrue(f.fake.callbacks.isEmpty())
            val polls = f.fake.offsets.size
            f.bot.pollOnce(); assertEquals(polls, f.fake.offsets.size)
            f.time += 25_000; f.bot.deliverOnce(); assertEquals(1, f.fake.sent.size)
        }
    }
    @Test fun permanentlyRejectedDeliveryDoesNotRetryIndefinitely() = runBlocking {
        Fixture().use { f ->
            f.rule(); f.optIn(); f.notifications.process(channel, f.publication())
            f.fake.sendError = BotApiException(403)
            f.bot.deliverOnce()
            assertEquals(1, f.bot.status().delivery.failed); assertEquals(0, f.bot.status().delivery.pending)
            f.time += 1_000_000; f.bot.deliverOnce(); assertTrue(f.fake.sent.isEmpty())
        }
    }
    @Test fun disabledBotStatusRestWorksWithoutTokenAndNeverIncludesCredentials() = testApplication {
        Repositories(database()).use { repo ->
            BotService(config(null), repo, SubscriptionService(repo, MatchingService()), { "unused" }, gatewayFactory = { error("disabled") }).use { bot ->
                application { routing { install(ContentNegotiation) { json() } }; configureBotRoutes(bot) }
                val response = client.get("/api/integrations/bot")
                assertEquals(200, response.status.value)
                val text = response.bodyAsText()
                assertContains(text, "disabled"); assertFalse(text.contains("token\"", ignoreCase = true))
            }
        }
    }
}
