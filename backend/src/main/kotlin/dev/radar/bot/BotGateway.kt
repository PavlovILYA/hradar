package dev.radar.bot

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Safe error only: no HTTP URL, token, response description or original exception. */
class BotApiException(val code: Int = 0, val retryAfterSeconds: Long? = null) : RuntimeException("Bot API request failed ($code)")
internal fun decodeBotResponse(status: Int, bytes: ByteArray): JsonElement {
    if (bytes.size > 2 * 1024 * 1024) throw BotApiException()
    val envelope = try { Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject } catch (_: Exception) { throw BotApiException(status) }
    if (status !in 200..299 || (envelope["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
        val code = (envelope["error_code"] as? JsonPrimitive)?.intOrNull ?: status
        val retry = ((envelope["parameters"] as? JsonObject)?.get("retry_after") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())
        throw BotApiException(code, retry)
    }
    return envelope["result"] ?: throw BotApiException()
}
interface BotGateway : AutoCloseable {
    suspend fun identity(): BotIdentity
    suspend fun updates(offset: Long): List<BotUpdate>
    suspend fun send(chatId: Long, payload: BotPayload): Long
    suspend fun answerCallback(id: String, text: String)
    override fun close() {}
}

class HttpsBotGateway(private val token: String) : BotGateway {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private suspend fun call(method: String, args: JsonObject, polling: Boolean = false): JsonElement {
        try {
            val request = HttpRequest.newBuilder(URI("https://api.telegram.org/bot$token/$method"))
                .timeout(Duration.ofSeconds(if (polling) 45 else 15))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(args.toString())).build()
            val response = suspendCancellableCoroutine<HttpResponse<ByteArray>> { continuation ->
                val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                continuation.invokeOnCancellation { future.cancel(true) }
                future.whenComplete { result, error ->
                    if (error != null) continuation.resumeWithException(BotApiException())
                    else continuation.resume(result)
                }
            }
            return withContext(Dispatchers.IO) {
                decodeBotResponse(response.statusCode(), response.body())
            }
        } catch (ex: CancellationException) { throw ex }
        catch (ex: BotApiException) { throw ex }
        catch (_: Exception) { throw BotApiException() }
    }
    override suspend fun identity(): BotIdentity {
        val result = call("getMe", buildJsonObject {}).jsonObject
        return BotIdentity(result.getValue("id").jsonPrimitive.long, result.getValue("username").jsonPrimitive.content)
    }
    override suspend fun updates(offset: Long): List<BotUpdate> {
        val result = call("getUpdates", buildJsonObject {
            put("offset", offset); put("timeout", 30); put("limit", 50)
            put("allowed_updates", buildJsonArray { add("message"); add("callback_query") })
        }, polling = true).jsonArray
        return result.mapNotNull { element ->
            val update = element.jsonObject
            val id = update["update_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            fun chat(value: JsonObject?) = value?.let { BotChat(it.getValue("id").jsonPrimitive.long, it.getValue("type").jsonPrimitive.content) }
            val message = update["message"]?.jsonObject?.let { m ->
                val from = m["from"]?.jsonObject ?: return@let null
                BotMessage(from.getValue("id").jsonPrimitive.long, chat(m["chat"]?.jsonObject) ?: return@let null, m["text"]?.jsonPrimitive?.contentOrNull, from["is_bot"]?.jsonPrimitive?.booleanOrNull ?: false)
            }
            val callback = update["callback_query"]?.jsonObject?.let { c ->
                val from = c.getValue("from").jsonObject
                BotCallback(c.getValue("id").jsonPrimitive.content, from.getValue("id").jsonPrimitive.long, chat(c["message"]?.jsonObject?.get("chat")?.jsonObject), c["data"]?.jsonPrimitive?.contentOrNull, from["is_bot"]?.jsonPrimitive?.booleanOrNull ?: false)
            }
            BotUpdate(id, message, callback)
        }
    }
    override suspend fun send(chatId: Long, payload: BotPayload): Long = call("sendMessage", buildJsonObject {
        put("chat_id", chatId); put("text", payload.text.take(3500))
        put("link_preview_options", buildJsonObject { put("is_disabled", true) })
        if (payload.buttons.isNotEmpty()) put("reply_markup", buildJsonObject { put("inline_keyboard", buildJsonArray {
            payload.buttons.forEach { row -> add(buildJsonArray { row.forEach { b -> add(buildJsonObject { put("text", b.text); put("callback_data", b.callback) }) } }) }
        }) })
    }).jsonObject.getValue("message_id").jsonPrimitive.long
    override suspend fun answerCallback(id: String, text: String) { call("answerCallbackQuery", buildJsonObject { put("callback_query_id", id); put("text", text.take(150)) }) }
    override fun close() { client.shutdownNow() }
}
