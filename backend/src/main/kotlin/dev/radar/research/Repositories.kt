package dev.radar.research

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import dev.radar.history.*
import dev.radar.bot.*
import dev.radar.telegram.TelegramMessage

/** All JDBC work is serialized, and invoked on Dispatchers.IO by services/routes. */
class Repositories(path: Path) : AutoCloseable {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val db: Connection
    private var botPolicyId: Long? = null
    private var botAllowedUsers: Set<Long> = emptySet()
    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        db = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        db.createStatement().use { s ->
            s.execute("PRAGMA foreign_keys=ON")
            s.execute("PRAGMA busy_timeout=5000")
            s.execute("PRAGMA journal_mode=WAL")
            val version = s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            check(version <= 5) { "Database schema is newer than this application" }
            if (version == 0) transaction {
                s.execute("CREATE TABLE monitored_channels(id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE COLLATE NOCASE, data TEXT NOT NULL)")
                s.execute("CREATE TABLE search_templates(id TEXT PRIMARY KEY, data TEXT NOT NULL)")
                s.execute("CREATE TABLE template_sources(template_id TEXT NOT NULL REFERENCES search_templates(id) ON DELETE CASCADE, channel_id INTEGER NOT NULL REFERENCES monitored_channels(id) ON DELETE CASCADE, PRIMARY KEY(template_id,channel_id))")
                s.execute("CREATE TABLE matched_messages(id TEXT PRIMARY KEY, template_id TEXT NOT NULL REFERENCES search_templates(id) ON DELETE CASCADE, channel_id INTEGER NOT NULL, message_id INTEGER NOT NULL, data TEXT NOT NULL, UNIQUE(template_id,channel_id,message_id))")
                s.execute("CREATE TABLE notifications(id TEXT PRIMARY KEY REFERENCES matched_messages(id) ON DELETE CASCADE, is_read INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)")
                s.execute("CREATE TABLE monitoring_state(channel_id INTEGER PRIMARY KEY REFERENCES monitored_channels(id) ON DELETE CASCADE, data TEXT NOT NULL)")
                s.execute("PRAGMA user_version=1")
            }
            if (version < 2) transaction {
                s.execute("CREATE TABLE history_sync(channel_id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE COLLATE NOCASE, status TEXT NOT NULL CHECK(status IN ('IDLE','RUNNING','COMPLETED','FAILED')), data TEXT NOT NULL)")
                s.execute("CREATE TABLE imported_messages(channel_id INTEGER NOT NULL, message_id INTEGER NOT NULL, date INTEGER NOT NULL, text TEXT, normalized_text TEXT NOT NULL, PRIMARY KEY(channel_id,message_id))")
                s.execute("PRAGMA user_version=2")
            }
            if (version < 3) transaction {
                s.execute("ALTER TABLE history_sync RENAME TO history_sync_v2")
                s.execute("CREATE TABLE history_sync(channel_id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE COLLATE NOCASE, status TEXT NOT NULL CHECK(status IN ('IDLE','RUNNING','COMPLETED','FAILED','PAUSED')), data TEXT NOT NULL)")
                s.execute("INSERT INTO history_sync SELECT * FROM history_sync_v2")
                s.execute("DROP TABLE history_sync_v2")
                s.execute("PRAGMA user_version=3")
            }
            if (version < 4) transaction {
                s.execute("CREATE TABLE mcp_settings(id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL DEFAULT 0)")
                s.execute("INSERT INTO mcp_settings VALUES(1,0)")
                s.execute("CREATE TABLE mcp_sources(channel_id INTEGER PRIMARY KEY, username TEXT NOT NULL, title TEXT NOT NULL)")
                s.execute("CREATE TABLE mcp_audit(id INTEGER PRIMARY KEY AUTOINCREMENT, called_at INTEGER NOT NULL, tool TEXT NOT NULL, result_code TEXT NOT NULL, duration_ms INTEGER NOT NULL)")
                s.execute("PRAGMA user_version=4")
            }
            if (version < 5) transaction {
                s.execute("CREATE TABLE bot_recipients(bot_id INTEGER NOT NULL,user_id INTEGER NOT NULL,chat_id INTEGER NOT NULL,registered_since INTEGER NOT NULL,active INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(bot_id,user_id),CHECK(user_id=chat_id AND user_id>0))")
                s.execute("CREATE TABLE bot_state(bot_id INTEGER PRIMARY KEY,next_offset INTEGER NOT NULL DEFAULT 0,poll_retry_at INTEGER NOT NULL DEFAULT 0,send_retry_at INTEGER NOT NULL DEFAULT 0,rate_retry_at INTEGER NOT NULL DEFAULT 0)")
                s.execute("CREATE TABLE bot_outbox(id INTEGER PRIMARY KEY AUTOINCREMENT,bot_id INTEGER NOT NULL,event_key TEXT NOT NULL,user_id INTEGER NOT NULL,chat_id INTEGER NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','SENT','FAILED','CANCELLED')),attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at INTEGER NOT NULL DEFAULT 0,last_error TEXT,sent_message_id INTEGER,UNIQUE(bot_id,event_key,user_id))")
                s.execute("CREATE INDEX bot_outbox_due ON bot_outbox(bot_id,state,next_attempt_at,id)")
                s.execute("PRAGMA user_version=5")
            }
        }
    }
    private fun <T> transaction(block: () -> T): T {
        db.autoCommit = false
        try { val result = block(); db.commit(); return result }
        catch (ex: Throwable) { db.rollback(); throw ex }
        finally { db.autoCommit = true }
    }
    private fun update(sql: String, vararg args: Any?): Int = db.prepareStatement(sql).use { s ->
        args.forEachIndexed { i, value -> s.setObject(i + 1, value) }; s.executeUpdate()
    }
    private fun <T> rows(sql: String, args: List<Any?> = emptyList(), read: (java.sql.ResultSet) -> T): List<T> = db.prepareStatement(sql).use { s ->
        args.forEachIndexed { i, value -> s.setObject(i + 1, value) }
        s.executeQuery().use { r -> buildList { while (r.next()) add(read(r)) } }
    }
    @Synchronized fun channels(): List<MonitoredChannel> = rows("SELECT data FROM monitored_channels ORDER BY username") { json.decodeFromString<MonitoredChannel>(it.getString(1)) }
    @Synchronized fun saveChannel(channel: MonitoredChannel): MonitoredChannel {
        require(channels().size < 10 || channels().any { it.id == channel.id }) { "MVP поддерживает до 10 отслеживаемых источников" }
        update("INSERT INTO monitored_channels VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET username=excluded.username,data=excluded.data", channel.id, channel.username, json.encodeToString(channel))
        return channel
    }
    @Synchronized fun deleteChannel(id: Long): Boolean = transaction {
        // Keep template JSON and normalized sources consistent, pausing empty selected templates.
        templates().filter { id in it.sources }.forEach { t ->
            val remaining = t.sources - id
            saveTemplate(t.copy(sources = remaining, subscribed = t.subscribed && remaining.isNotEmpty(), active = t.active && (t.sourceMode != "selected" || remaining.isNotEmpty())))
        }
        update("DELETE FROM monitored_channels WHERE id=?", id) > 0
    }
    @Synchronized fun templates(): List<SearchTemplate> = rows("SELECT data FROM search_templates ORDER BY rowid") { json.decodeFromString<SearchTemplate>(it.getString(1)) }
    @Synchronized fun template(id: String): SearchTemplate? = templates().find { it.id == id }
    @Synchronized fun saveTemplate(template: SearchTemplate): SearchTemplate {
        val previous = this.template(template.id)
        val since = if (template.active && template.subscribed && (previous == null || !previous.active || !previous.subscribed)) (System.currentTimeMillis() / 1000).toInt() else previous?.subscriptionSince ?: 0
        val saved = template.copy(id = template.id.ifEmpty { UUID.randomUUID().toString() }, subscriptionSince = since)
        require(saved.sources.all { id -> channels().any { it.id == id } }) { "Источник не сохранён" }
        // Reentrant synchronized calls are intentional. Do not nest JDBC transactions.
        fun write() {
            update("INSERT INTO search_templates VALUES(?,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data", saved.id, json.encodeToString(saved))
            update("DELETE FROM template_sources WHERE template_id=?", saved.id)
            saved.sources.forEach { update("INSERT INTO template_sources VALUES(?,?)", saved.id, it) }
        }
        if (db.autoCommit) transaction { write() } else write()
        return saved
    }
    @Synchronized fun deleteTemplate(id: String): Boolean = update("DELETE FROM search_templates WHERE id=?", id) > 0
    @Synchronized fun recordMatch(template: SearchTemplate, message: ResearchMessage): Boolean = transaction {
        if (this.template(template.id) == null) return@transaction false
        val record = NotificationRecord(UUID.randomUUID().toString(), template.id, template.name, message)
        val inserted = update("INSERT INTO matched_messages VALUES(?,?,?,?,?) ON CONFLICT(template_id,channel_id,message_id) DO NOTHING", record.id, template.id, message.channelId, message.id, json.encodeToString(record))
        if (inserted > 0) {
            update("INSERT INTO notifications VALUES(?,0,?)", record.id, System.currentTimeMillis())
            val botId = botPolicyId
            if (botId != null) {
                val payload = matchPayload(record.templateName, message.title, message.username, message.text, message.link, message.channelId, message.id)
                rows("SELECT user_id,chat_id FROM bot_recipients WHERE bot_id=? AND active=1 AND registered_since<=?", listOf(botId, message.date)) { it.getLong(1) to it.getLong(2) }
                    .filter { it.first in botAllowedUsers }.forEach { (userId, chatId) -> enqueueBot(botId, "match:${record.id}", userId, chatId, payload) }
            }
        }
        inserted > 0
    }
    @Synchronized fun notifications(limit: Int = 50, before: Long = Long.MAX_VALUE): List<NotificationRecord> = rows("SELECT m.data,n.is_read,n.rowid FROM notifications n JOIN matched_messages m ON m.id=n.id WHERE n.rowid<? ORDER BY n.rowid DESC LIMIT ?", listOf(before, limit)) {
        json.decodeFromString<NotificationRecord>(it.getString(1)).copy(read = it.getInt(2) == 1, sequence = it.getLong(3))
    }
    @Synchronized fun unreadByChannel(): Map<Long, Int> = rows("SELECT m.channel_id,COUNT(*) FROM notifications n JOIN matched_messages m ON m.id=n.id WHERE n.is_read=0 GROUP BY m.channel_id") { it.getLong(1) to it.getInt(2) }.toMap()
    @Synchronized fun unreadCount(): Int = rows("SELECT COUNT(*) FROM notifications WHERE is_read=0") { it.getInt(1) }.single()
    @Synchronized fun markRead(id: String): Boolean = update("UPDATE notifications SET is_read=1 WHERE id=?", id) > 0
    @Synchronized fun markAllRead() { update("UPDATE notifications SET is_read=1") }
    @Synchronized fun state(channelId: Long): MonitoringState? = rows("SELECT data FROM monitoring_state WHERE channel_id=?", listOf(channelId)) { json.decodeFromString<MonitoringState>(it.getString(1)) }.firstOrNull()
    @Synchronized fun saveState(state: MonitoringState) { update("INSERT INTO monitoring_state VALUES(?,?) ON CONFLICT(channel_id) DO UPDATE SET data=excluded.data", state.channelId, json.encodeToString(state)) }

    @Synchronized fun historySyncs(): List<HistorySyncRecord> = rows("SELECT data FROM history_sync ORDER BY username") { json.decodeFromString<HistorySyncRecord>(it.getString(1)) }
    @Synchronized fun historySync(channelId: Long): HistorySyncRecord? = rows("SELECT data FROM history_sync WHERE channel_id=?", listOf(channelId)) { json.decodeFromString<HistorySyncRecord>(it.getString(1)) }.firstOrNull()
    @Synchronized fun historySyncByUsername(username: String): HistorySyncRecord? = rows("SELECT data FROM history_sync WHERE username=?", listOf(username)) { json.decodeFromString<HistorySyncRecord>(it.getString(1)) }.firstOrNull()
    private fun writeHistoryState(state: HistorySyncRecord) {
        update("INSERT INTO history_sync VALUES(?,?,?,?) ON CONFLICT(channel_id) DO UPDATE SET username=excluded.username,status=excluded.status,data=excluded.data", state.channelId, state.username, state.status.name, json.encodeToString(state))
    }
    @Synchronized fun beginHistorySync(channel: MonitoredChannel, maxMessages: Int? = 1000): HistorySyncRecord {
        require(maxMessages == null || maxMessages in setOf(1000, 5000, 10000)) { "Лимит: 1000, 5000, 10000 или null для всей истории" }
        val old = historySync(channel.id)
        if (old?.status in setOf(SyncStatus.RUNNING, SyncStatus.COMPLETED)) return old!!
        val state = (old ?: HistorySyncRecord(channel.id, channel.username, channel.title)).copy(status = SyncStatus.RUNNING, retryCount = 0, error = null, maxMessages = maxMessages, pauseReason = null, updatedAt = System.currentTimeMillis())
        writeHistoryState(state)
        return state
    }
    @Synchronized fun saveHistoryPage(state: HistorySyncRecord, messages: List<TelegramMessage>): HistorySyncRecord = transaction {
        val normalize = MatchingService()
        db.prepareStatement("INSERT INTO imported_messages VALUES(?,?,?,?,?) ON CONFLICT(channel_id,message_id) DO UPDATE SET date=excluded.date,text=excluded.text,normalized_text=excluded.normalized_text").use { s ->
            messages.forEach { message ->
                require(message.channelId == state.channelId) { "History channel mismatch" }
                s.setLong(1, message.channelId); s.setLong(2, message.id); s.setInt(3, message.date); s.setString(4, message.text); s.setString(5, normalize.normalize(message.text.orEmpty())); s.addBatch()
            }
            s.executeBatch()
        }
        val count = rows("SELECT COUNT(*) FROM imported_messages WHERE channel_id=?", listOf(state.channelId)) { it.getInt(1) }.single()
        state.copy(messageCount = count, updatedAt = System.currentTimeMillis()).also(::writeHistoryState)
    }
    @Synchronized fun historyMessages(channelId: Long, limit: Int, before: Long?, query: String): HistoryPage {
        require(limit in 1..100)
        val normalized = MatchingService().normalize(query)
        val total = rows("SELECT COUNT(*) FROM imported_messages WHERE channel_id=? AND instr(normalized_text,?)>0", listOf(channelId, normalized)) { it.getInt(1) }.single()
        val page = rows("SELECT message_id,date,text FROM imported_messages WHERE channel_id=? AND message_id<? AND instr(normalized_text,?)>0 ORDER BY message_id DESC LIMIT ?", listOf(channelId, before ?: Long.MAX_VALUE, normalized, limit + 1)) { TelegramMessage(it.getLong(1), it.getInt(2), it.getString(3), channelId) }
        val messages = page.take(limit)
        return HistoryPage(messages, messages.lastOrNull()?.id?.takeIf { page.size > limit }, total, query)
    }
    @Synchronized fun mcpEnabled(): Boolean = rows("SELECT enabled FROM mcp_settings WHERE id=1") { it.getInt(1) != 0 }.single()
    @Synchronized fun setMcpEnabled(enabled: Boolean) { update("UPDATE mcp_settings SET enabled=? WHERE id=1", if (enabled) 1 else 0) }
    @Synchronized fun mcpSources(): List<MonitoredChannel> = rows("SELECT channel_id,username,title FROM mcp_sources ORDER BY channel_id") { MonitoredChannel(it.getLong(1), it.getString(2), it.getString(3)) }
    @Synchronized fun allowMcpSource(channel: MonitoredChannel) { require(channel.id < 0 && channel.username.matches(Regex("[a-zA-Z0-9_]{5,32}"))); update("INSERT INTO mcp_sources VALUES(?,?,?) ON CONFLICT(channel_id) DO UPDATE SET username=excluded.username,title=excluded.title", channel.id, channel.username, channel.title) }
    @Synchronized fun revokeMcpSource(id: Long) { update("DELETE FROM mcp_sources WHERE channel_id=?", id) }
    @Synchronized fun auditMcp(tool: String, code: String, duration: Long) {
        update("INSERT INTO mcp_audit(called_at,tool,result_code,duration_ms) VALUES(?,?,?,?)", System.currentTimeMillis(), tool, code, duration)
        update("DELETE FROM mcp_audit WHERE id NOT IN (SELECT id FROM mcp_audit ORDER BY id DESC LIMIT 500)")
    }

    @Synchronized fun saveValidatedTemplate(template: SearchTemplate, matching: MatchingService, mustExist: Boolean): SearchTemplate {
        if (mustExist && this.template(template.id) == null) throw ResourceNotFound()
        return saveTemplate(matching.validate(template))
    }
    @Synchronized fun setSubscriptionActive(id: String, active: Boolean, matching: MatchingService): SearchTemplate {
        val template = template(id) ?: throw ResourceNotFound()
        require(template.subscribed && template.sourceMode != "global") { "Это не подписка на мониторинг" }
        return saveTemplate(matching.validate(template.copy(active = active)))
    }
    @Synchronized fun configureBot(botId: Long?, allowed: Set<Long>) {
        botPolicyId = botId; botAllowedUsers = allowed.toSet()
        if (botId != null) {
            update("INSERT INTO bot_state(bot_id) VALUES(?) ON CONFLICT DO NOTHING", botId)
            rows("SELECT DISTINCT user_id FROM bot_outbox WHERE bot_id=? AND state='PENDING'", listOf(botId)) { it.getLong(1) }
                .filter { it !in allowed }.forEach { update("UPDATE bot_outbox SET state='CANCELLED',payload='' WHERE bot_id=? AND user_id=? AND state='PENDING'", botId, it) }
            rows("SELECT user_id FROM bot_recipients WHERE bot_id=? AND active=1", listOf(botId)) { it.getLong(1) }
                .filter { it !in allowed }.forEach { update("UPDATE bot_recipients SET active=0 WHERE bot_id=? AND user_id=?", botId, it) }
        }
    }
    /** Command mutation, its reply and polling cursor commit together; replay cannot reapply pause/resume. */
    @Synchronized fun handleBotUpdate(botId: Long, updateId: Long, handle: () -> Unit): Boolean = transaction {
        if (updateId < botOffset(botId)) return@transaction false
        handle()
        update("UPDATE bot_state SET next_offset=? WHERE bot_id=?", updateId + 1, botId)
        true
    }
    @Synchronized fun registerBotRecipient(botId: Long, userId: Long, chatId: Long, now: Long) {
        require(botId == botPolicyId && userId in botAllowedUsers && userId == chatId && userId > 0)
        update("INSERT INTO bot_recipients VALUES(?,?,?,?,1) ON CONFLICT(bot_id,user_id) DO UPDATE SET chat_id=excluded.chat_id,registered_since=CASE WHEN active=0 THEN excluded.registered_since ELSE registered_since END,active=1", botId, userId, chatId, now / 1000)
    }
    @Synchronized fun stopBotRecipient(botId: Long, userId: Long) {
        update("UPDATE bot_recipients SET active=0 WHERE bot_id=? AND user_id=?", botId, userId)
        update("UPDATE bot_outbox SET state='CANCELLED',payload='' WHERE bot_id=? AND user_id=? AND state='PENDING'", botId, userId)
    }
    @Synchronized fun botRegistered(botId: Long, userId: Long): Boolean = rows("SELECT active FROM bot_recipients WHERE bot_id=? AND user_id=?", listOf(botId, userId)) { it.getInt(1) == 1 }.firstOrNull() == true
    @Synchronized fun botRegisteredCount(botId: Long): Int = rows("SELECT COUNT(*) FROM bot_recipients WHERE bot_id=? AND active=1", listOf(botId)) { it.getInt(1) }.single()
    @Synchronized fun botOffset(botId: Long): Long = rows("SELECT next_offset FROM bot_state WHERE bot_id=?", listOf(botId)) { it.getLong(1) }.firstOrNull() ?: 0
    @Synchronized fun botRateRetryAt(botId: Long): Long = rows("SELECT rate_retry_at FROM bot_state WHERE bot_id=?", listOf(botId)) { it.getLong(1) }.firstOrNull() ?: 0
    @Synchronized fun setBotRateRetryAt(botId: Long, at: Long) { update("UPDATE bot_state SET rate_retry_at=MAX(rate_retry_at,?) WHERE bot_id=?", at, botId) }
    @Synchronized fun botRetryAt(botId: Long, polling: Boolean): Long = rows("SELECT ${if (polling) "poll_retry_at" else "send_retry_at"} FROM bot_state WHERE bot_id=?", listOf(botId)) { it.getLong(1) }.firstOrNull() ?: 0
    @Synchronized fun setBotRetryAt(botId: Long, polling: Boolean, at: Long) { update("UPDATE bot_state SET ${if (polling) "poll_retry_at" else "send_retry_at"}=? WHERE bot_id=?", at, botId) }
    @Synchronized fun enqueueBot(botId: Long, key: String, userId: Long, chatId: Long, payload: BotPayload) {
        require(botId == botPolicyId && userId in botAllowedUsers && chatId == userId)
        update("INSERT INTO bot_outbox(bot_id,event_key,user_id,chat_id,payload) VALUES(?,?,?,?,?) ON CONFLICT(bot_id,event_key,user_id) DO NOTHING", botId, key, userId, chatId, json.encodeToString(payload))
    }
    @Synchronized fun nextBotDelivery(botId: Long, now: Long): BotOutboxItem? = rows("SELECT o.id,o.user_id,o.chat_id,o.payload,o.attempts FROM bot_outbox o JOIN bot_recipients r ON r.bot_id=o.bot_id AND r.user_id=o.user_id WHERE o.bot_id=? AND o.state='PENDING' AND o.next_attempt_at<=? AND r.active=1 ORDER BY o.id LIMIT 1", listOf(botId, now)) { BotOutboxItem(it.getLong(1), it.getLong(2), it.getLong(3), json.decodeFromString<BotPayload>(it.getString(4)), it.getInt(5)) }.firstOrNull()
    @Synchronized fun prepareBotDelivery(id: Long, payload: BotPayload) { update("UPDATE bot_outbox SET payload=? WHERE id=? AND state='PENDING'", json.encodeToString(payload), id) }
    @Synchronized fun confirmBotDelivery(id: Long, messageId: Long) { update("UPDATE bot_outbox SET state='SENT',sent_message_id=?,payload='',last_error=NULL WHERE id=? AND state='PENDING'", messageId, id) }
    @Synchronized fun retryBotDelivery(id: Long, at: Long, code: String, permanent: Boolean) {
        update("UPDATE bot_outbox SET state=?,attempts=attempts+1,next_attempt_at=?,last_error=? WHERE id=? AND state='PENDING'", if (permanent) "FAILED" else "PENDING", at, code, id)
    }
    @Synchronized fun botDeliveryCounts(botId: Long): BotDeliveryCounts {
        val counts = rows("SELECT state,COUNT(*) FROM bot_outbox WHERE bot_id=? GROUP BY state", listOf(botId)) { it.getString(1) to it.getInt(2) }.toMap()
        return BotDeliveryCounts(counts["PENDING"] ?: 0, counts["SENT"] ?: 0, counts["FAILED"] ?: 0)
    }
    @Synchronized override fun close() { db.close() }
}
