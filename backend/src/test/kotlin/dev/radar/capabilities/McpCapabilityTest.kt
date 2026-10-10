package dev.radar.capabilities

import dev.radar.research.*
import dev.radar.ApiError
import dev.radar.respondApiError
import dev.radar.telegram.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.testing.testApplication
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class McpCapabilityTest {
    private val channel = MonitoredChannel(-1001, "synthetic_channel", "Synthetic")
    private class Fake(private val channel: MonitoredChannel) : TelegramReadPort {
        var calls = 0; var error: TelegramRequestException? = null
        override fun status() = TelegramStatus("Ready", true, "Ready")
        private fun check() { calls++; error?.let { throw it } }
        override suspend fun resolve(username: String): MonitoredChannel { check(); return channel }
        override suspend fun history(channelId: Long, cursor: Long): List<TelegramMessage> {
            check(); return when(cursor) { 0L -> listOf(30L,20L); 20L -> listOf(20L,10L); else -> emptyList() }.map { TelegramMessage(it, 1700000000, "Synthetic Java", channelId) }
        }
        override suspend fun search(username: String, query: String, limit: Int, cursor: String): SearchPage { check(); return SearchPage(listOf(research(20)), "opaque") }
        private fun research(id: Long) = ResearchMessage(id, channel.id, 1700000000, "Synthetic Java", channel.title, channel.username)
        override suspend fun message(channel: MonitoredChannel, id: Long): ResearchMessage { check(); return research(id) }
        override suspend fun neighbors(channel: MonitoredChannel, id: Long, count: Int): List<ResearchMessage> { check(); return listOf(research(30), research(10)) }
    }
    private fun repo() = Repositories(Files.createTempDirectory("hradar-mcp-test").resolve("db.sqlite"))
    private fun args(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun allow(repo: Repositories) { repo.setMcpEnabled(true); repo.allowMcpSource(channel) }

    @Test fun registrationAndSchemasExposeBoundedOperationsWithOneConfirmationRequest() {
        assertEquals(12, McpTools.definitions.size)
        assertEquals(12, McpTools.definitions.map { it.name }.toSet().size)
        McpTools.definitions.forEach {
            assertEquals("object", it.schema["type"]!!.jsonPrimitive.content)
            it.schema["required"]!!.jsonArray.forEach { field -> assertTrue(field.jsonPrimitive.content in it.schema["properties"]!!.jsonObject) }
            it.schema["properties"]!!.jsonObject["limit"]?.let { limit -> assertEquals(50, limit.jsonObject["maximum"]!!.jsonPrimitive.int) }
            assertTrue(it.description.contains("untrusted"))
        }
        assertFalse(McpTools.definitions.any { it.name.contains("send") || it.name.contains("execute") || it.name.contains("grant") })
    }
    @Test fun recentMatchesDetailsAndPublishRespectAllowlistAndSeparatePermission() = runBlocking {
        repo().use { repo ->
            val denied = MonitoredChannel(-1002, "synthetic_denied", "Denied")
            val rule = repo.saveTemplate(SearchTemplate(name = "Synthetic rule", telegramQuery = "Java"))
            repo.recordMatch(rule, ResearchMessage(1, channel.id, 1700000000, "Synthetic Java", channel.title, channel.username))
            repo.recordMatch(rule, ResearchMessage(2, denied.id, 1700000000, "Denied content", denied.title, denied.username))
            val records = repo.notifications()
            val permitted = records.single { it.message.channelId == channel.id }.id
            val hidden = records.single { it.message.channelId == denied.id }.id
            val fake = Fake(channel); val service = CapabilityService(fake, repo)
            allow(repo)
            val page = service.call("list_recent_matches", args("{}"))
            assertTrue(page.ok); assertFalse(page.data.toString().contains("Denied"))
            assertEquals(1, page.data!!["matches"]!!.jsonArray.size)
            assertEquals("ACCESS_DENIED", service.call("get_match_details", args("""{"matchId":"$hidden"}""")).error!!.code)
            assertTrue(service.call("get_match_details", args("""{"matchId":"$permitted"}""")).ok)
            assertEquals("INVALID_ARGUMENTS", service.call("list_recent_matches", args("""{"limit":51}""")).error!!.code)
            val publishArgs = args("""{"recipientUserId":7,"matchIds":["$permitted"]}""")
            assertEquals("ACCESS_DENIED", service.call("bot_publish_matches", publishArgs).error!!.code)
            repo.configureBot(123, setOf(7)); repo.registerBotRecipient(123, 7, 7, 1700000000000)
            repo.allowBotPublish(123, 7, true)
            val request = service.call("bot_publish_matches", publishArgs)
            assertTrue(request.ok); assertEquals("AWAITING_USER_CONFIRMATION", request.data!!["state"]!!.jsonPrimitive.content)
            assertEquals(1, repo.botDeliveryCounts(123).pending)
            assertEquals("ACCESS_DENIED", service.call("bot_publish_matches", args("""{"recipientUserId":7,"matchIds":["$hidden"]}""")).error!!.code)
            assertEquals("INVALID_ARGUMENTS", service.call("bot_publish_matches", args("""{"recipientUserId":7,"matchIds":["$permitted"],"text":"arbitrary"}""")).error!!.code)
            repo.revokeMcpSource(channel.id)
            assertEquals(0, repo.botDeliveryCounts(123).pending)
            assertEquals("ACCESS_DENIED", service.call("get_match_details", args("""{"matchId":"$permitted"}""")).error!!.code)
            assertEquals(0, fake.calls)
        }
    }
    @Test fun disabledUnlistedAndPrivateChatsCannotReachTelegram() = runBlocking {
        repo().use { repo ->
            val fake = Fake(channel); val service = CapabilityService(fake, repo)
            assertEquals("DISABLED", service.call("list_chats", args("{}")).error!!.code)
            allow(repo)
            for (chat in listOf("12345", "private_user", "not_allowed")) assertEquals("ACCESS_DENIED", service.call("resolve_chat", args("""{"chat":"$chat"}""")).error!!.code)
            assertEquals(0, fake.calls)
            assertEquals("UNSUPPORTED", service.call("SendMessage", args("{}")).error!!.code)
            assertEquals("INVALID_ARGUMENTS", service.call("get_telegram_status", args("""{"execute":"code"}""")).error!!.code)
        }
    }
    @Test fun validatesLimitsAndPaginatesInclusiveHistoryWithoutDuplicates() = runBlocking {
        repo().use { repo ->
            allow(repo); val fake = Fake(channel); val service = CapabilityService(fake, repo)
            val first = service.call("get_chat_history", args("""{"chat":"synthetic_channel","limit":2}""")); assertTrue(first.ok)
            assertEquals(listOf(30L,20L), first.data!!["messages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long })
            assertEquals(20L, first.data!!["nextCursor"]!!.jsonPrimitive.long)
            val second = service.call("get_chat_history", args("""{"chat":"-1001","cursor":20,"limit":2}""")); assertTrue(second.ok)
            assertEquals(listOf(10L), second.data!!["messages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long })
            for (limit in listOf(0,51,1000)) assertEquals("INVALID_ARGUMENTS", service.call("get_chat_history", args("""{"chat":"-1001","limit":$limit}""")).error!!.code)
            assertEquals("INVALID_ARGUMENTS", service.call("get_chat_history", args("""{"chat":"-1001","rawFunction":"GetMe"}""")).error!!.code)
        }
    }
    @Test fun searchContextAndEphemeralDraftAreTypedAndNeverSend() = runBlocking {
        repo().use { repo ->
            allow(repo); val fake = Fake(channel); val service = CapabilityService(fake, repo)
            val search = service.call("search_chat_messages", args("""{"chat":"-1001","query":"Java"}""")); assertEquals("opaque", search.data!!["nextCursor"]!!.jsonPrimitive.content)
            val context = service.call("get_message_context", args("""{"chat":"-1001","messageId":20}""")); assertEquals(2, context.data!!["neighbors"]!!.jsonArray.size)
            val calls = fake.calls
            val draft = service.call("prepare_message", args("""{"chat":"-1001","text":"Synthetic draft"}""")); assertTrue(draft.ok)
            assertFalse(draft.data!!["sent"]!!.jsonPrimitive.boolean); assertEquals(calls, fake.calls)
            assertEquals("INVALID_ARGUMENTS", service.call("get_message_context", args("""{"chat":"-1001","messageId":20,"neighbors":11}""")).error!!.code)
        }
    }
    @Test fun localArchiveAndRulesStayWithinAllowlistAndClipLargeTexts() = runBlocking {
        repo().use { repo ->
            allow(repo); val state = repo.beginHistorySync(channel)
            repo.saveHistoryPage(state.copy(cursor = 1), listOf(TelegramMessage(2, 1700000000, "Java " + "x".repeat(3000), channel.id), TelegramMessage(1, 1700000000, "JAVA short", channel.id)))
            val other = MonitoredChannel(-2001, "private_other", "Hidden")
            repo.saveChannel(channel); repo.saveChannel(other)
            repo.saveTemplate(SearchTemplate(name="Synthetic", telegramQuery="Java", sourceMode="selected", sources=listOf(channel.id,other.id), subscribed=true))
            val service = CapabilityService(Fake(channel), repo)
            val page = service.call("search_local_archive", args("""{"chat":"-1001","query":"java","limit":1}""")); assertTrue(page.ok)
            assertEquals(2, page.data!!["total"]!!.jsonPrimitive.int); assertEquals("sqlite", page.data!!["source"]!!.jsonPrimitive.content)
            val text = page.data!!["messages"]!!.jsonArray.single().jsonObject; assertTrue(text["truncated"]!!.jsonPrimitive.boolean); assertEquals(2000,text["text"]!!.jsonPrimitive.content.length)
            val rules = service.call("list_monitoring_rules", args("{}")); assertFalse(rules.data.toString().contains("-2001"))
        }
    }
    @Test fun telegramErrorsAreSanitizedAndWaitDurationIsStructured() = runBlocking {
        repo().use { repo ->
            allow(repo); val fake = Fake(channel); fake.error = TelegramRequestException(429,"FLOOD_WAIT_42 internal-secret")
            val result = CapabilityService(fake,repo).call("resolve_chat",args("""{"chat":"-1001"}"""))
            assertEquals("RATE_LIMITED",result.error!!.code); assertEquals(42L,result.error!!.retryAfterSeconds)
            assertFalse(result.toString().contains("internal-secret"))
        }
    }
    @Test fun restAndStandardMcpRequestsShareServiceAndManagementCannotBeCalledByMcp() = testApplication {
        repo().use { repo ->
            allow(repo); val fake = Fake(channel); val service = CapabilityService(fake, repo)
            application { routing { install(ContentNegotiation) { json(Json { encodeDefaults=true }) } }; configureCapabilities(service,fake,repo) }
            suspend fun rpc(method: String, params: String="{}") = client.post("/mcp") {
                header(HttpHeaders.Host,"127.0.0.1:8080"); header(HttpHeaders.Accept,"application/json, text/event-stream")
                contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")
            }
            val initialized = rpc("initialize","""{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""")
            assertEquals(HttpStatusCode.OK,initialized.status)
            val listed = args(rpc("tools/list").bodyAsText())["result"]!!.jsonObject["tools"]!!.jsonArray
            assertEquals(12,listed.size)
            listed.forEach { item ->
                val tool = item.jsonObject
                assertEquals(tool["name"]!!.jsonPrimitive.content != "bot_publish_matches", tool["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
            }
            val emptyMatches = args(rpc("tools/call", """{"name":"list_recent_matches","arguments":{}}""").bodyAsText())["result"]!!.jsonObject
            assertTrue(emptyMatches["structuredContent"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
            val blockedPublish = args(rpc("tools/call", """{"name":"bot_publish_matches","arguments":{"recipientUserId":7,"matchIds":["00000000-0000-0000-0000-000000000000"]}}""").bodyAsText())["result"]!!.jsonObject
            assertTrue(blockedPublish["isError"]!!.jsonPrimitive.boolean)
            val mcp = args(rpc("tools/call","""{"name":"get_telegram_status","arguments":{}}""").bodyAsText())["result"]!!.jsonObject["structuredContent"]!!.jsonObject
            val rest = client.post("/api/capabilities/get_telegram_status") { header(HttpHeaders.Host,"127.0.0.1:8080"); contentType(ContentType.Application.Json); setBody("{}") }
            assertEquals(mcp,args(rest.bodyAsText()))
            assertEquals(HttpStatusCode.Forbidden, client.put("/api/integrations/mcp") { header(HttpHeaders.Host,"127.0.0.1:8080"); contentType(ContentType.Application.Json);setBody("""{"enabled":false}""") }.status)
            assertTrue(repo.mcpEnabled())
            val denied = rpc("tools/call","""{"name":"get_chat_history","arguments":{"chat":"12345"}}""")
            assertTrue(args(denied.bodyAsText())["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
            assertTrue(args(rpc("tools/call","""{"name":"grant_access","arguments":{}}""").bodyAsText())["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
            assertEquals(listOf(channel), repo.mcpSources()); assertEquals(0, fake.calls)
            assertEquals(HttpStatusCode.Forbidden,client.post("/mcp") {header(HttpHeaders.Host,"evil.example:8080");setBody("{}")} .status)
            assertEquals(HttpStatusCode.Forbidden,client.post("/mcp") {header(HttpHeaders.Host,"127.0.0.1:8080");header(HttpHeaders.Origin,"http://evil.example");setBody("{}")} .status)
        }
    }
    @Test fun legacyRestErrorsRemainJsonAlongsideTheMcpSerializer() = testApplication {
        repo().use { repo ->
            allow(repo); val fake = Fake(channel)
            application {
                routing { install(ContentNegotiation) { json(Json { encodeDefaults=true }) } }
                install(StatusPages) { exception<IllegalArgumentException> { call, _ -> call.respondApiError(HttpStatusCode.BadRequest,ApiError("Synthetic invalid input")) } }
                configureCapabilities(CapabilityService(fake,repo),fake,repo)
                routing { get("/existing-error") { throw IllegalArgumentException() } }
            }
            val response = client.get("/existing-error")
            assertEquals(HttpStatusCode.BadRequest,response.status)
            assertEquals("Synthetic invalid input",args(response.bodyAsText())["error"]!!.jsonPrimitive.content)
            assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
        }
    }
    @Test fun allowlistAndDisabledPreferencePersistWithoutTouchingArchive() {
        val path = Files.createTempDirectory("hradar-mcp-persistence").resolve("db.sqlite")
        Repositories(path).use { repo ->
            val state = repo.beginHistorySync(channel)
            repo.saveHistoryPage(state.copy(cursor=1),listOf(TelegramMessage(1,1700000000,"Synthetic",channel.id)))
            allow(repo); repo.setMcpEnabled(false)
            assertFailsWith<IllegalArgumentException> { repo.allowMcpSource(MonitoredChannel(12345,"private_user","Private")) }
        }
        Repositories(path).use { repo ->
            assertFalse(repo.mcpEnabled()); assertEquals(listOf(channel),repo.mcpSources())
            assertEquals(1,repo.historyMessages(channel.id,10,null,"").total)
            repo.revokeMcpSource(channel.id); assertTrue(repo.mcpSources().isEmpty())
        }
    }
    @Test fun exactHostAndOriginChecksRejectRebindingAndCrossSiteManagement() {
        assertTrue(LocalMcpSecurity.valid("127.0.0.1:8080",null,false))
        assertFalse(LocalMcpSecurity.valid("localhost.evil:8080",null,false))
        assertFalse(LocalMcpSecurity.valid("127.0.0.1:8080","http://127.0.0.1:9999",true))
        assertFalse(LocalMcpSecurity.valid("127.0.0.1:8080",null,true))
        assertTrue(LocalMcpSecurity.valid("127.0.0.1:5173","http://127.0.0.1:5173",true))
    }
}
