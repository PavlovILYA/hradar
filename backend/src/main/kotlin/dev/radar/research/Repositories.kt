package dev.radar.research

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import dev.radar.history.*
import dev.radar.telegram.TelegramMessage

/** All JDBC work is serialized, and invoked on Dispatchers.IO by services/routes. */
class Repositories(path: Path) : AutoCloseable {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val db: Connection
    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        db = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        db.createStatement().use { s ->
            s.execute("PRAGMA foreign_keys=ON")
            s.execute("PRAGMA busy_timeout=5000")
            s.execute("PRAGMA journal_mode=WAL")
            val version = s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            check(version <= 2) { "Database schema is newer than this application" }
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
        if (inserted > 0) update("INSERT INTO notifications VALUES(?,0,?)", record.id, System.currentTimeMillis())
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
    @Synchronized fun beginHistorySync(channel: MonitoredChannel): HistorySyncRecord {
        val old = historySync(channel.id)
        if (old?.status in setOf(SyncStatus.RUNNING, SyncStatus.COMPLETED)) return old!!
        val state = (old ?: HistorySyncRecord(channel.id, channel.username, channel.title)).copy(status = SyncStatus.RUNNING, retryCount = 0, error = null, updatedAt = System.currentTimeMillis())
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
    @Synchronized override fun close() { db.close() }
}
