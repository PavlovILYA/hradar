package dev.radar.bot

import dev.radar.research.*
import dev.radar.telegram.*
import dev.radar.capabilities.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.ZoneId
import kotlin.test.*

class MobileRecruiterTest {
    private val source = MonitoredChannel(-1001, "synthetic_jobs", "Synthetic jobs")
    private val other = MonitoredChannel(-1002, "synthetic_other", "Other jobs")
    private fun publication(id: Long, channel: MonitoredChannel = source, date: Int = 1700000000) = ResearchMessage(id, channel.id, date, "Synthetic java spring remote $id", channel.title, channel.username, "https://t.me/${channel.username}/$id")
    private class FakeGateway : BotGateway {
        var input = emptyList<BotUpdate>(); val sent = mutableListOf<BotPayload>()
        override suspend fun identity() = BotIdentity(123, "synthetic_bot")
        override suspend fun updates(offset: Long) = input.filter { it.id >= offset }
        override suspend fun send(chatId: Long, payload: BotPayload): Long { sent.add(payload); return sent.size.toLong() }
        override suspend fun answerCallback(id: String, text: String) {}
    }
    private inner class Fixture(val path: Path = Files.createTempDirectory("hradar-mobile-test").resolve("research.sqlite")) : AutoCloseable {
        val repo = Repositories(path); val gateway = FakeGateway(); val matching = MatchingService()
        var time = 1700000000000L; val requests = mutableListOf<SearchRequest>()
        var searchError: RuntimeException? = null
        var searchAction: suspend (SearchRequest) -> SearchPage = { input ->
            SearchPage(if (input.cursor.isEmpty()) listOf(publication(10), publication(9)) else listOf(publication(8)), if (input.cursor.isEmpty()) "page2" else "")
        }
        val recruiter = RecruiterService(repo, RecruiterSearchPort { input -> requests.add(input); searchError?.let { throw it }; searchAction(input) })
        val subscriptions = SubscriptionService(repo, matching)
        val workspace = BotWorkspaceService(repo, recruiter, { time }, ZoneId.of("Europe/Moscow"))
        val config = BotConfig.fromEnvironment { when (it) { "TG_BOT_TOKEN" -> "123:synthetic_not_real"; "TG_BOT_ALLOWED_USER_IDS" -> "7,8"; else -> null } }
        val bot = BotService(config, repo, subscriptions, { "ready" }, now = { time }, link = { channel, message -> "https://t.me/${repo.channels().first { it.id == channel }.username}/$message" }, gatewayFactory = { gateway }, workspace = workspace)
        var sequence = maxOf(1, repo.botOffset(123))
        var lastUpdate: BotUpdate? = null
        init { repo.saveChannel(source); repo.saveChannel(other) }
        suspend fun update(value: BotUpdate): BotPayload? {
            gateway.input = listOf(value); lastUpdate = value; bot.pollOnce(); gateway.input = emptyList()
            val start = gateway.sent.size
            repeat(20) {
                if (repo.nextBotDelivery(123, time) != null) { time += 2000; bot.deliverOnce() }
            }
            return gateway.sent.drop(start).lastOrNull()
        }
        suspend fun text(value: String, user: Long = 7): BotPayload? = update(BotUpdate(sequence++, message = BotMessage(user, BotChat(user, "private"), value)))
        suspend fun click(payload: BotPayload, action: String, user: Long = 7): BotPayload {
            val data = payload.buttons.flatten().first { it.callback.split(':').drop(2).joinToString(":") == action }.callback
            return update(BotUpdate(sequence++, callback = BotCallback("callback-$sequence", user, BotChat(user, "private"), data, messageId = 100)))!!
        }
        suspend fun optIn(user: Long = 7) { text("/start", user); gateway.sent.clear() }
        suspend fun find(mode: String = "telegram", selection: String = "all"): BotPayload {
            val choose = text("/find java")!!; val sources = click(choose, "mode:$mode"); return click(sources, selection)
        }
        override fun close() { bot.close(); repo.close() }
    }
    @Test fun phoneSearchNavigationSaveWatchAndExistingNotificationsFormOneWorkflow() = runBlocking {
        Fixture().use { f ->
            f.optIn()
            var card = f.find()
            assertEquals(listOf("synthetic_jobs", "synthetic_other").sorted(), f.requests.single().usernames.sorted())
            assertEquals("tracked", f.requests.single().mode); assertEquals(10, f.requests.single().limit)
            assertTrue(card.buttons.flatten().any { it.url == publication(10).link })
            card = f.click(card, "next"); assertContains(card.text, "remote 9")
            card = f.click(card, "prev"); assertContains(card.text, "remote 10")
            card = f.click(card, "save"); assertContains(card.text, "Сохранено")
            assertEquals(1, f.repo.savedResults(status = "SAVED").items.size)
            f.update(f.lastUpdate!!); assertEquals(1, f.repo.savedResults(status = "SAVED").items.size)
            f.click(card, "watch")
            f.text("Java Spring remote"); f.text("java, spring"); f.text("-"); var select = f.text("junior")!!
            select = f.click(select, "source:${other.id}")
            val preview = f.click(select, "preview")
            assertContains(preview.text, "java, spring"); assertEquals(0, f.subscriptions.list().size)
            val created = f.click(preview, "confirm"); assertContains(created.text, "создана")
            assertEquals(1, f.subscriptions.list().size)
            val rule = f.subscriptions.list().single()
            assertTrue(rule.subscribed); assertEquals(listOf(source.id), rule.sources)
            assertEquals(listOf("java", "spring"), rule.required)
            f.update(f.lastUpdate!!); assertEquals(1, f.subscriptions.list().size)
            val date = (System.currentTimeMillis() / 1000).toInt() + 1
            val notifications = NotificationService(f.repo, f.matching)
            assertEquals(1, notifications.process(source, publication(20, date = date)))
            assertEquals(0, notifications.process(source, publication(20, date = date)))
            f.time = date * 1000L + 1000
            assertEquals(1, f.repo.botDeliveryCounts(123).pending)
            f.bot.deliverOnce(); assertContains(f.gateway.sent.last().text, rule.name)
            val digest = f.text("/digest")!!; assertContains(digest.text, "за период: 1"); assertContains(digest.text, "Непросмотренных: 1")
            assertTrue(digest.buttons.flatten().any { it.callback.startsWith("match:") })
        }
    }
    @Test fun rejectionIsDurablePerOperatorAndStaleOrOtherUserCardsCannotMutateSelection() = runBlocking {
        val path = Files.createTempDirectory("hradar-mobile-restart").resolve("research.sqlite")
        Fixture(path).use { f ->
            f.optIn(); f.optIn(8)
            val old = f.find(); val next = f.click(old, "next")
            val stale = f.click(old, "save"); assertContains(stale.text, "устарела")
            assertTrue(f.repo.savedResults().items.isEmpty())
            var fresh = f.find(); fresh = f.click(fresh, "dismiss")
            assertEquals("REJECTED", f.repo.publicationStatus(123, 7, source.id, 10))
            assertContains(fresh.text, "remote 9")
            f.click(fresh, "save", 8) // other operator has no matching workflow nonce
            assertEquals(0, f.repo.savedResults(user = 8).items.size)
            assertTrue(next.text.contains("remote 9"))
        }
        Fixture(path).use { f ->
            f.time += 100000
            val card = f.find(); assertFalse(card.text.contains("remote 10")); assertContains(card.text, "remote 9")
            val own = f.text("/find java", 8)!!
            val sources = f.click(own, "mode:telegram", 8)
            val otherCard = f.click(sources, "all", 8); assertContains(otherCard.text, "remote 10")
        }
    }
    @Test fun savedRemovalAndDuplicateUniqueKeyPersistAcrossRestart() = runBlocking {
        Fixture().use { f ->
            f.optIn(); var card = f.find(); card = f.click(card, "save")
            f.recruiter.decide(123, 7, publication(10), "SAVED")
            assertEquals(1, f.repo.savedResults(status = "SAVED").items.size)
            val saved = f.text("/saved")!!; assertContains(saved.text, "Избранное")
            f.click(saved, "remove")
            assertTrue(f.repo.savedResults(status = "SAVED").items.isEmpty())
            assertEquals("REMOVED", f.repo.savedResults().items.single().status)
            assertTrue(card.text.contains("Сохранено"))
        }
    }
    @Test fun archiveSearchUsesSQLiteAndBoundedKeysetPaginationWithoutTelegram() = runBlocking {
        Fixture().use { f ->
            val state = f.repo.beginHistorySync(source)
            f.repo.saveHistoryPage(state, (1L..25L).map { TelegramMessage(it, 1700000000, "Synthetic java archive $it", source.id) })
            f.optIn(); var card = f.find("archive", "source:${source.id}")
            assertContains(card.text, "Локальный архив"); assertContains(card.text, "archive 25")
            assertTrue(card.buttons.flatten().any { it.url == "https://t.me/synthetic_jobs/25" })
            card = f.click(card, "save")
            assertEquals("https://t.me/synthetic_jobs/25", f.repo.savedResults(status = "SAVED").items.single().message.link)
            repeat(9) { card = f.click(card, "next") }
            card = f.click(card, "more"); assertContains(card.text, "archive 15")
            repeat(9) { card = f.click(card, "next") }
            card = f.click(card, "more"); assertContains(card.text, "archive 5")
            assertTrue(f.requests.isEmpty())
            assertTrue(f.repo.workspace(123, 7, f.time)!!.messages.size <= 50)
            assertFailsWith<IllegalArgumentException> { f.recruiter.find("java", "archive", listOf(source.id), "not-a-cursor") }
        }
    }
    @Test fun savedPaginationNeverSkipsTheEleventhPublication() = runBlocking {
        Fixture().use { f ->
            f.optIn()
            (1L..21L).forEach { f.recruiter.decide(123, 7, publication(it), "SAVED") }
            var card = f.text("/saved")!!; assertContains(card.text, "remote 21")
            repeat(9) { card = f.click(card, "next") }
            assertContains(card.text, "remote 12")
            card = f.click(card, "savedMore"); assertContains(card.text, "remote 11")
            repeat(9) { card = f.click(card, "next") }
            card = f.click(card, "savedMore"); assertContains(card.text, "remote 1")
            assertTrue(card.buttons.flatten().none { it.callback.endsWith(":savedMore") })
        }
    }
    @Test fun searchCapsPagesDeduplicatesAndHonorsFloodWaitWithoutAdvancingCursor() = runBlocking {
        Fixture().use { f ->
            f.optIn(); f.searchError = TelegramRequestException(429, "FLOOD_WAIT_30 synthetic-secret")
            var card = f.find(); assertContains(card.text, "30 сек"); assertFalse(card.text.contains("secret"))
            card = f.click(card, "more"); assertEquals(1, f.requests.size)
            f.time += 31000; f.searchError = null
            f.searchAction = { input -> SearchPage(listOf(publication(10), publication(9)), "page-${f.requests.size}") }
            card = f.click(card, "more"); assertContains(card.text, "remote 10")
            card = f.click(card, "next")
            repeat(4) { card = f.click(card, "more") }
            assertEquals(6, f.requests.size) // one rate-limited attempt + five bounded pages
            assertEquals(2, f.repo.workspace(123, 7, f.time)!!.messages.size)
            assertTrue(f.repo.workspace(123, 7, f.time)!!.cursor.isEmpty())
        }
    }
    @Test fun unauthorizedChatsNeverSearchOrPersistAndDialogCanCancelAndResume() = runBlocking {
        val path = Files.createTempDirectory("hradar-watch-resume").resolve("research.sqlite")
        Fixture(path).use { f ->
            assertNull(f.text("/find java", 9)); assertNull(f.text("/find java"))
            f.optIn(); assertNull(f.text("/find@another_bot java"))
            f.update(BotUpdate(f.sequence++, message = BotMessage(7, BotChat(-10, "group"), "/find java")))
            assertTrue(f.requests.isEmpty())
            assertEquals(f.sequence, f.repo.botOffset(123))
            f.text("/watch"); f.text("Persistent rule")
        }
        Fixture(path).use { f ->
            f.time += 10000
            assertContains(f.text("java")!!.text, "Альтернативные")
            f.text("-"); val choose = f.text("-")!!
            f.click(choose, "source:${source.id}")
            f.text("/cancel"); assertNull(f.repo.workspace(123, 7, f.time)); assertTrue(f.subscriptions.list().isEmpty())
        }
    }
    @Test fun decisionAndReplyRollbackTogetherAndDeletedWatchSourceCannotCreateRule() = runBlocking {
        Fixture().use { f ->
            f.optIn(); val card = f.find()
            val before = f.repo.botOffset(123)
            DriverManager.getConnection("jdbc:sqlite:${f.path}").use { db -> db.createStatement().use { s ->
                s.execute("CREATE TRIGGER reject_mobile_reply BEFORE INSERT ON bot_outbox BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
            } }
            assertFailsWith<java.sql.SQLException> { f.click(card, "save") }
            assertEquals(before, f.repo.botOffset(123)); assertTrue(f.repo.savedResults().items.isEmpty())
            DriverManager.getConnection("jdbc:sqlite:${f.path}").use { db -> db.createStatement().use { it.execute("DROP TRIGGER reject_mobile_reply") } }
            f.update(f.lastUpdate!!); assertEquals(1, f.repo.savedResults(status = "SAVED").items.size)
            f.text("/watch"); f.text("Synthetic watch"); f.text("java"); f.text("-")
            val sources = f.text("-")!!; val selected = f.click(sources, "source:${source.id}")
            val preview = f.click(selected, "preview")
            f.repo.deleteChannel(source.id)
            val response = f.click(preview, "confirm"); assertContains(response.text, "изменились")
            assertTrue(f.subscriptions.list().isEmpty()); assertEquals(f.sequence, f.repo.botOffset(123))
        }
    }
    @Test fun cancellationKeepsWorkflowAndCursorWhileSourceRemovalDiscardsNetworkResult() = runBlocking {
        Fixture().use { f ->
            f.optIn()
            val mode = f.text("/find java")!!; val sources = f.click(mode, "mode:telegram")
            val data = sources.buttons.flatten().first { it.callback.endsWith(":all") }.callback
            val update = BotUpdate(f.sequence++, callback = BotCallback("synthetic-cancel", 7, BotChat(7, "private"), data, messageId = 100))
            val offset = f.repo.botOffset(123); val entered = CompletableDeferred<Unit>()
            f.searchAction = { entered.complete(Unit); awaitCancellation() }
            f.gateway.input = listOf(update)
            val job = launch { f.bot.pollOnce() }; entered.await(); job.cancelAndJoin()
            assertEquals(offset, f.repo.botOffset(123)); assertEquals("sources", f.repo.workspace(123, 7, f.time)!!.step)
            assertEquals(0, f.repo.botDeliveryCounts(123).pending)
            f.searchAction = { f.repo.deleteChannel(source.id); SearchPage(listOf(publication(10))) }
            f.gateway.input = listOf(update); f.bot.pollOnce(); f.time += 2000; f.bot.deliverOnce()
            assertFalse(f.gateway.sent.last().text.contains("Synthetic java"))
            assertEquals(update.id + 1, f.repo.botOffset(123))
        }
    }
    @Test fun digestUsesRealPublicationDatesAndUnreadCountsForTodayAndSevenDays() = runBlocking {
        Fixture().use { f ->
            f.optIn(); f.time = 1700100000000L
            val rule = f.repo.saveTemplate(SearchTemplate(name = "Synthetic digest", required = listOf("java"), telegramQuery = "java"))
            val now = (f.time / 1000).toInt()
            f.repo.recordMatch(rule, publication(1, date = now - 30))
            f.repo.recordMatch(rule, publication(2, date = now - 3 * 86400))
            f.repo.recordMatch(rule, publication(3, date = now - 8 * 86400))
            f.repo.markRead(f.repo.notifications().single { it.message.id == 1L }.id)
            val today = f.text("/digest")!!; assertContains(today.text, "за период: 1"); assertContains(today.text, "Непросмотренных: 0")
            val week = f.click(today, "period:week"); assertContains(week.text, "за период: 2"); assertContains(week.text, "Непросмотренных: 1")
            f.recruiter.decide(123, 7, publication(2), "REJECTED")
            val newToday = f.text("/digest")!!; val filtered = f.click(newToday, "period:week")
            assertContains(filtered.text, "за период: 1")
        }
    }
    @Test fun productionSearchServiceReusedThroughFakeTelegramExecutor() = runBlocking {
        val directory = Files.createTempDirectory("hradar-mobile-search-port")
        Repositories(directory.resolve("research.sqlite")).use { repo ->
            repo.saveChannel(source)
            val gateway = TdlibGateway(1, "synthetic_unused", directory)
            val calls = mutableListOf<TdApi.Function<*>>()
            val service = SearchService(gateway, repo, MatchingService()) { query ->
                calls.add(query)
                when (query) {
                    is TdApi.SearchChatMessages -> { assertEquals(source.id, query.chatId); assertEquals("java", query.query); TdApi.FoundChatMessages(1, arrayOf(TdApi.Message().apply { id = 10; chatId = source.id; date = 1700000000; content = TdApi.MessageText().apply { text = TdApi.FormattedText("Synthetic java", emptyArray()) } }), 0) }
                    is TdApi.GetMessageLink -> TdApi.MessageLink().apply { link = "https://t.me/synthetic_jobs/10" }
                    else -> error("Unexpected Telegram operation")
                }
            }
            val recruiter = RecruiterService(repo, RecruiterSearchPort(service::search))
            val page = recruiter.find("java", "telegram", listOf(source.id))
            assertEquals(1, page.messages.size); assertEquals(2, calls.size)
            assertTrue(calls.none { it.javaClass.simpleName.contains("Join") })
        }
    }
}
