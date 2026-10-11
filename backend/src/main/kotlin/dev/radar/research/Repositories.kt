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
            check(version <= 8) { "Database schema is newer than this application" }
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
            if (version < 6) transaction {
                s.execute("CREATE TABLE bot_publish_permission(bot_id INTEGER NOT NULL,user_id INTEGER NOT NULL,PRIMARY KEY(bot_id,user_id))")
                s.execute("CREATE TABLE bot_publish_request(id TEXT PRIMARY KEY,bot_id INTEGER NOT NULL,user_id INTEGER NOT NULL,match_ids TEXT NOT NULL,expires_at INTEGER NOT NULL,state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','CONFIRMED','CANCELLED')))")
                s.execute("CREATE INDEX bot_publish_expiry ON bot_publish_request(bot_id,user_id,expires_at)")
                s.execute("PRAGMA user_version=6")
            }
            if (version < 7) transaction {
                s.execute("CREATE TABLE bot_workspace(bot_id INTEGER NOT NULL,user_id INTEGER NOT NULL,data TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(bot_id,user_id))")
                s.execute("CREATE TABLE publication_decisions(sequence INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,bot_id INTEGER NOT NULL,user_id INTEGER NOT NULL,channel_id INTEGER NOT NULL,message_id INTEGER NOT NULL,status TEXT NOT NULL CHECK(status IN ('SAVED','REJECTED','REMOVED','VIEWED')),data TEXT NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(bot_id,user_id,channel_id,message_id))")
                s.execute("CREATE INDEX publication_review ON publication_decisions(bot_id,user_id,status,sequence)")
                s.execute("ALTER TABLE matched_messages ADD COLUMN message_date INTEGER NOT NULL DEFAULT 0")
                rows("SELECT id,data FROM matched_messages") { it.getString(1) to json.decodeFromString<NotificationRecord>(it.getString(2)).message.date }.forEach { (id, date) -> update("UPDATE matched_messages SET message_date=? WHERE id=?", date, id) }
                s.execute("CREATE INDEX matches_date ON matched_messages(message_date)")
                s.execute("PRAGMA user_version=7")
            }
            if (version < 8) transaction {
                s.execute("CREATE TABLE IF NOT EXISTS recruitment_projects(id TEXT PRIMARY KEY,status TEXT NOT NULL CHECK(status IN ('ACTIVE','ARCHIVED')),name TEXT NOT NULL,data TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS recruitment_templates(project_id TEXT NOT NULL REFERENCES recruitment_projects(id) ON DELETE CASCADE,template_id TEXT NOT NULL REFERENCES search_templates(id) ON DELETE CASCADE,PRIMARY KEY(project_id,template_id))")
                s.execute("CREATE TABLE IF NOT EXISTS recruitment_cards(id TEXT PRIMARY KEY,project_id TEXT NOT NULL REFERENCES recruitment_projects(id) ON DELETE CASCADE,channel_id INTEGER NOT NULL,message_id INTEGER NOT NULL,stage TEXT NOT NULL CHECK(stage IN ('NEW','INTERESTING','CONTACTED','IN_PROGRESS','REJECTED')),interest INTEGER NOT NULL CHECK(interest BETWEEN 0 AND 5),normalized_text TEXT NOT NULL,normalized_notes TEXT NOT NULL,data TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(project_id,channel_id,message_id))")
                s.execute("CREATE INDEX IF NOT EXISTS recruitment_board ON recruitment_cards(project_id,stage,updated_at,id)")
                s.execute("CREATE INDEX IF NOT EXISTS recruitment_source ON recruitment_cards(project_id,channel_id)")
                s.execute("CREATE INDEX IF NOT EXISTS recruitment_project_status ON recruitment_projects(status,updated_at,id)")
                s.execute("CREATE TABLE IF NOT EXISTS recruitment_publications(channel_id INTEGER NOT NULL,message_id INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(channel_id,message_id))")
                s.execute("PRAGMA user_version=8")
            }

        }
    }
    private fun <T> transaction(block: () -> T): T {
        db.autoCommit = false
        try { val result = block(); db.commit(); return result }
        catch (ex: Throwable) { db.rollback(); throw ex }
        finally { db.autoCommit = true }
    }
    private fun <T> atomic(block: () -> T): T = if (db.autoCommit) transaction(block) else block()
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
    @Synchronized fun recordMatch(template: SearchTemplate, message: ResearchMessage, monitoring: MatchingService? = null): Boolean = transaction {
        val current = this.template(template.id) ?: return@transaction false
        val effectiveTemplate = if (monitoring == null) {
            if (current != template) return@transaction false
            template
        } else {
            // Recheck live rules and source permission in the same transaction as the
            // notification/outbox write. UI edits/removal may race the worker's snapshot.
            if (!current.active || !current.subscribed || current.sourceMode == "global" ||
                message.date < current.subscriptionSince || channels().none { it.id == message.channelId } ||
                current.sourceMode == "selected" && message.channelId !in current.sources) return@transaction false
            current
        }
        val evidence = if (monitoring == null) message else {
            val explanation = monitoring.check(effectiveTemplate, message.text)
            if (!explanation.matches) return@transaction false
            message.copy(explanation = explanation)
        }
        val record = NotificationRecord(UUID.randomUUID().toString(), effectiveTemplate.id, effectiveTemplate.name, evidence)
        val inserted = update("INSERT INTO matched_messages(id,template_id,channel_id,message_id,data,message_date) VALUES(?,?,?,?,?,?) ON CONFLICT(template_id,channel_id,message_id) DO NOTHING", record.id, template.id, message.channelId, message.id, json.encodeToString(record), message.date)
        if (inserted > 0) {
            update("INSERT INTO notifications VALUES(?,0,?)", record.id, System.currentTimeMillis())
            val botId = botPolicyId
            if (botId != null) {
                val payload = inboxPayload(record).let { it.copy(buttons = listOf(listOf(BotButton("Просмотреть", "match:${record.id}"))) + it.buttons) }
                rows("SELECT user_id,chat_id FROM bot_recipients WHERE bot_id=? AND active=1 AND registered_since<=?", listOf(botId, message.date)) { it.getLong(1) to it.getLong(2) }
                    .filter { it.first in botAllowedUsers && publicationStatus(botId, it.first, message.channelId, message.id) != "REJECTED" }.forEach { (userId, chatId) -> enqueueBot(botId, "match:${record.id}", userId, chatId, payload) }
            }
        }
        inserted > 0
    }
    @Synchronized fun notifications(limit: Int = 50, before: Long = Long.MAX_VALUE): List<NotificationRecord> = rows("SELECT m.data,n.is_read,n.rowid FROM notifications n JOIN matched_messages m ON m.id=n.id WHERE n.rowid<? ORDER BY n.rowid DESC LIMIT ?", listOf(before, limit)) {
        json.decodeFromString<NotificationRecord>(it.getString(1)).copy(read = it.getInt(2) == 1, sequence = it.getLong(3))
    }
    @Synchronized fun recentMatches(limit: Int, before: Long = Long.MAX_VALUE, sources: Set<Long>? = null, botId: Long? = null, user: Long? = null): List<NotificationRecord> {
        require(limit in 1..50 && before > 0)
        if (sources != null && sources.isEmpty()) return emptyList()
        val scope = sources?.let { " AND m.channel_id IN (${it.joinToString(",") { "?" }})" }.orEmpty() + if (botId != null && user != null) " AND NOT EXISTS(SELECT 1 FROM publication_decisions d WHERE d.bot_id=? AND d.user_id=? AND d.channel_id=m.channel_id AND d.message_id=m.message_id AND d.status='REJECTED')" else ""
        return rows("SELECT m.data,n.is_read,n.rowid FROM notifications n JOIN matched_messages m ON m.id=n.id WHERE n.rowid<?$scope ORDER BY n.rowid DESC LIMIT ?", listOf(before) + sources.orEmpty().toList() + (if (botId != null && user != null) listOf(botId, user) else emptyList()) + limit) {
            json.decodeFromString<NotificationRecord>(it.getString(1)).copy(read = it.getInt(2) == 1, sequence = it.getLong(3))
        }
    }
    @Synchronized fun newerMatch(sequence: Long, botId: Long? = null, user: Long? = null): NotificationRecord? {
        val scope = if (botId != null && user != null) " AND NOT EXISTS(SELECT 1 FROM publication_decisions d WHERE d.bot_id=? AND d.user_id=? AND d.channel_id=m.channel_id AND d.message_id=m.message_id AND d.status='REJECTED')" else ""
        val args = listOf<Any?>(sequence) + if (botId != null && user != null) listOf(botId, user) else emptyList()
        return rows("SELECT m.data,n.is_read,n.rowid FROM notifications n JOIN matched_messages m ON m.id=n.id WHERE n.rowid>?$scope ORDER BY n.rowid ASC LIMIT 1", args) {
            json.decodeFromString<NotificationRecord>(it.getString(1)).copy(read = it.getInt(2) == 1, sequence = it.getLong(3))
        }.firstOrNull()
    }
    @Synchronized fun match(id: String): NotificationRecord? = rows("SELECT m.data,n.is_read,n.rowid FROM notifications n JOIN matched_messages m ON m.id=n.id WHERE m.id=?", listOf(id)) {
        json.decodeFromString<NotificationRecord>(it.getString(1)).copy(read = it.getInt(2) == 1, sequence = it.getLong(3))
    }.firstOrNull()
    @Synchronized fun allowBotPublish(botId: Long, user: Long, enabled: Boolean): Unit = atomic {
        require(botId == botPolicyId && user in botAllowedUsers && botRegistered(botId, user))
        if (enabled) update("INSERT OR IGNORE INTO bot_publish_permission VALUES(?,?)", botId, user)
        else {
            update("DELETE FROM bot_publish_permission WHERE bot_id=? AND user_id=?", botId, user)
            update("UPDATE bot_publish_request SET state='CANCELLED' WHERE bot_id=? AND user_id=? AND state='PENDING'", botId, user)
            update("UPDATE bot_outbox SET state='CANCELLED',payload='' WHERE bot_id=? AND user_id=? AND event_key LIKE 'publish:%' AND state='PENDING'", botId, user)
        }
    }
    @Synchronized fun botPublishPermitted(user: Long): Boolean = botPolicyId?.let { publishAllowed(it, user) } == true
    private fun publishAllowed(botId: Long, user: Long) = botId == botPolicyId && user in botAllowedUsers && botRegistered(botId, user) &&
        rows("SELECT 1 FROM bot_publish_permission WHERE bot_id=? AND user_id=?", listOf(botId, user)) { true }.isNotEmpty()
    @Synchronized fun requestBotPublish(user: Long, ids: List<String>, now: Long): String = transaction {
        val botId = botPolicyId ?: throw IllegalArgumentException()
        require(publishAllowed(botId, user))
        require(ids.size in 1..10 && ids.distinct().size == ids.size)
        val allowed = mcpSources().map { it.id }.toSet()
        require(mcpEnabled() && ids.all { match(it)?.message?.channelId in allowed })
        // Bound requests and preview spam; excerpts are shown only to the consenting recipient.
        require(rows("SELECT COUNT(*) FROM bot_publish_request WHERE bot_id=? AND user_id=? AND expires_at>?", listOf(botId, user, now)) { it.getInt(1) }.single() < 3)
        update("DELETE FROM bot_publish_request WHERE expires_at<=?", now)
        val id = UUID.randomUUID().toString()
        update("INSERT INTO bot_publish_request(id,bot_id,user_id,match_ids,expires_at) VALUES(?,?,?,?,?)", id, botId, user, json.encodeToString(ids), now + 600_000)
        enqueueBot(botId, "publish:preview:$id", user, user, BotPayload("Агент запросил публикацию ${ids.size} совпадений в этот личный чат. Подтверждение действует 10 минут.\n\n" + ids.mapIndexed { index, matchId ->
            val record = match(matchId)!!
            "${index + 1}. ${record.message.title.take(80)} · ${record.templateName.take(60)}\n${record.message.text.orEmpty().replace(Regex("\\s+"), " ").take(120)}"
        }.joinToString("\n\n"), ids.mapIndexed { i, matchId -> listOf(BotButton("Просмотреть ${i + 1}", "review:$matchId")) } + listOf(listOf(BotButton("Подтвердить", "publish:$id"), BotButton("Отмена", "reject:$id"))), publishRequestId = id))
        id
    }
    @Synchronized fun confirmBotPublish(botId: Long, user: Long, id: String, accept: Boolean, now: Long): Boolean = atomic {
        val ids = rows("SELECT match_ids FROM bot_publish_request WHERE id=? AND bot_id=? AND user_id=? AND state='PENDING' AND expires_at>?", listOf(id, botId, user, now)) { json.decodeFromString<List<String>>(it.getString(1)) }.firstOrNull() ?: return@atomic false
        val records = ids.mapNotNull(::match)
        val sources = mcpSources().map { it.id }.toSet()
        val valid = accept && publishAllowed(botId, user) && mcpEnabled() && records.size == ids.size && records.all { it.message.channelId in sources }
        update("UPDATE bot_publish_request SET state=? WHERE id=?", if (valid) "CONFIRMED" else "CANCELLED", id)
        if (valid) records.forEach { record -> enqueueBot(botId, "publish:result:$id:${record.id}", user, user, dev.radar.bot.inboxPayload(record).copy(mcpSourceId = record.message.channelId)) }
        valid
    }
    @Synchronized fun workspace(botId: Long, user: Long, now: Long): BotWorkspace? = rows("SELECT data FROM bot_workspace WHERE bot_id=? AND user_id=? AND expires_at>?", listOf(botId, user, now)) { json.decodeFromString<BotWorkspace>(it.getString(1)) }.firstOrNull()
    @Synchronized fun saveWorkspace(botId: Long, user: Long, state: BotWorkspace?, now: Long) {
        require(botId == botPolicyId && user in botAllowedUsers && botRegistered(botId, user))
        update("DELETE FROM bot_workspace WHERE expires_at<=?", now)
        if (state == null) update("DELETE FROM bot_workspace WHERE bot_id=? AND user_id=?", botId, user)
        else {
            require(state.messages.size <= 50)
            update("INSERT INTO bot_workspace VALUES(?,?,?,?) ON CONFLICT(bot_id,user_id) DO UPDATE SET data=excluded.data,expires_at=excluded.expires_at", botId, user, json.encodeToString(state), now + 3_600_000)
        }
    }
    @Synchronized fun publicationStatus(botId: Long, user: Long, channel: Long, message: Long): String? = rows("SELECT status FROM publication_decisions WHERE bot_id=? AND user_id=? AND channel_id=? AND message_id=?", listOf(botId, user, channel, message)) { it.getString(1) }.firstOrNull()
    @Synchronized fun decidePublication(botId: Long, user: Long, message: ResearchMessage, status: String) {
        require(botId == botPolicyId && user in botAllowedUsers && botRegistered(botId, user))
        require(status in setOf("SAVED", "REJECTED", "REMOVED", "VIEWED") && message.channelId < 0 && message.id > 0)
        require(channels().any { it.id == message.channelId } || (status in setOf("REMOVED", "REJECTED") && publicationStatus(botId, user, message.channelId, message.id) != null)) { "Источник больше не сохранён" }
        update("INSERT INTO publication_decisions(id,bot_id,user_id,channel_id,message_id,status,data,updated_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(bot_id,user_id,channel_id,message_id) DO UPDATE SET status=excluded.status,data=excluded.data,updated_at=excluded.updated_at", UUID.randomUUID().toString(), botId, user, message.channelId, message.id, status, json.encodeToString(message.copy(text = message.text?.take(4000), link = verifiedTelegramLink(message.link))), System.currentTimeMillis())
    }
    @Synchronized fun rememberPublicationLink(botId: Long, user: Long, channel: Long, message: Long, link: String) = atomic {
        val original = verifiedTelegramLink(link) ?: return@atomic
        val record = rows("SELECT id,data FROM publication_decisions WHERE bot_id=? AND user_id=? AND channel_id=? AND message_id=?", listOf(botId, user, channel, message)) { it.getString(1) to json.decodeFromString<ResearchMessage>(it.getString(2)) }.firstOrNull()
        record?.let { update("UPDATE publication_decisions SET data=? WHERE id=?", json.encodeToString(it.second.copy(link = original)), it.first) }
        val state = rows("SELECT data FROM bot_workspace WHERE bot_id=? AND user_id=?", listOf(botId, user)) { json.decodeFromString<BotWorkspace>(it.getString(1)) }.firstOrNull()
        if (state != null && state.messages.any { it.channelId == channel && it.id == message }) {
            update("UPDATE bot_workspace SET data=? WHERE bot_id=? AND user_id=?", json.encodeToString(state.copy(messages = state.messages.map { if (it.channelId == channel && it.id == message) it.copy(link = original) else it })), botId, user)
        }
    }
    @Synchronized fun savedResult(id: String): SavedResult? = rows("SELECT id,status,data,sequence,updated_at FROM publication_decisions WHERE id=?", listOf(id)) { SavedResult(it.getString(1), it.getString(2), json.decodeFromString(it.getString(3)), it.getLong(4), it.getLong(5)) }.firstOrNull()
    @Synchronized fun savedResults(limit: Int = 25, before: Long = Long.MAX_VALUE, botId: Long? = null, user: Long? = null, sources: Set<Long>? = null, status: String? = null): SavedResultsPage {
        require(limit in 1..50 && before > 0 && (status == null || status in setOf("SAVED", "REJECTED", "REMOVED", "VIEWED")))
        if (sources != null && sources.isEmpty()) return SavedResultsPage(emptyList())
        val conditions = mutableListOf("sequence<?"); val args = mutableListOf<Any?>(before)
        botId?.let { conditions.add("bot_id=?"); args.add(it) }; user?.let { conditions.add("user_id=?"); args.add(it) }
        sources?.let { conditions.add("channel_id IN (${it.joinToString(",") { "?" }})"); args.addAll(it) }
        status?.let { conditions.add("status=?"); args.add(it) }
        args.add(limit + 1)
        val page = rows("SELECT id,status,data,sequence,updated_at FROM publication_decisions WHERE ${conditions.joinToString(" AND ")} ORDER BY sequence DESC LIMIT ?", args) { SavedResult(it.getString(1), it.getString(2), json.decodeFromString(it.getString(3)), it.getLong(4), it.getLong(5)) }
        return SavedResultsPage(page.take(limit), page.take(limit).lastOrNull()?.sequence?.takeIf { page.size > limit })
    }
    @Synchronized fun matchDigest(since: Int, until: Int, limit: Int = 10, before: Long = Long.MAX_VALUE, botId: Long? = null, user: Long? = null): MatchDigest {
        require(limit in 1..50 && since >= 0 && until > since && before > 0)
        val filter = "m.message_date>=? AND m.message_date<?" + if (botId != null && user != null) " AND NOT EXISTS(SELECT 1 FROM publication_decisions d WHERE d.bot_id=? AND d.user_id=? AND d.channel_id=m.channel_id AND d.message_id=m.message_id AND d.status='REJECTED')" else ""
        val args = listOf<Any?>(since, until) + if (botId != null && user != null) listOf(botId, user) else emptyList()
        val counts = rows("SELECT COUNT(*),COALESCE(SUM(CASE WHEN n.is_read=0 THEN 1 ELSE 0 END),0) FROM notifications n JOIN matched_messages m ON m.id=n.id WHERE $filter", args) { it.getInt(1) to it.getInt(2) }.single()
        val page = rows("SELECT m.data,n.is_read,n.rowid FROM notifications n JOIN matched_messages m ON m.id=n.id WHERE $filter AND n.rowid<? ORDER BY n.rowid DESC LIMIT ?", args + listOf(before, limit + 1)) { json.decodeFromString<NotificationRecord>(it.getString(1)).copy(read = it.getInt(2) == 1, sequence = it.getLong(3)) }
        return MatchDigest(counts.first, counts.second, page.take(limit), page.take(limit).lastOrNull()?.sequence?.takeIf { page.size > limit })
    }
    @Synchronized fun unreadByChannel(): Map<Long, Int> = rows("SELECT m.channel_id,COUNT(*) FROM notifications n JOIN matched_messages m ON m.id=n.id WHERE n.is_read=0 GROUP BY m.channel_id") { it.getLong(1) to it.getInt(2) }.toMap()
    @Synchronized fun unreadCount(): Int = rows("SELECT COUNT(*) FROM notifications WHERE is_read=0") { it.getInt(1) }.single()
    @Synchronized fun markRead(id: String): Boolean = update("UPDATE notifications SET is_read=1 WHERE id=?", id) > 0
    @Synchronized fun markAllRead() { update("UPDATE notifications SET is_read=1") }
    @Synchronized fun state(channelId: Long): MonitoringState? = rows("SELECT data FROM monitoring_state WHERE channel_id=?", listOf(channelId)) { json.decodeFromString<MonitoringState>(it.getString(1)) }.firstOrNull()
    @Synchronized fun saveState(state: MonitoringState) {
        // A source can disappear while an IO operation is suspended. Late checkpoints
        // must neither recreate it nor kill the polling worker with a foreign-key error.
        update("INSERT INTO monitoring_state SELECT ?,? WHERE EXISTS(SELECT 1 FROM monitored_channels WHERE id=?) ON CONFLICT(channel_id) DO UPDATE SET data=excluded.data",
            state.channelId, json.encodeToString(state), state.channelId)
    }

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
    /** One aggregate query for the selected sources; never loads the archive. */
    @Synchronized fun recruitmentArchiveSize(sources: List<Long>): Long {
        if (sources.isEmpty()) return 0
        return rows("SELECT COUNT(*) FROM imported_messages WHERE channel_id IN (${sources.joinToString(",") { "?" }})", sources) { it.getLong(1) }.single()
    }
    /** A bounded batch lookup, independent of the number of cards on the board. */
    @Synchronized fun recruitmentAddedKeys(projectId: String, messages: List<ResearchMessage>): List<String> {
        require(messages.size <= 10)
        if (messages.isEmpty()) return emptyList()
        val clauses = messages.joinToString(" OR ") { "(channel_id=? AND message_id=?)" }
        val args = listOf<Any>(projectId) + messages.flatMap { listOf(it.channelId, it.id) }
        return rows("SELECT channel_id,message_id FROM recruitment_cards WHERE project_id=? AND ($clauses)", args) { "${it.getLong(1)}:${it.getLong(2)}" }
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
    private fun cancelAgentPublications() {
        update("UPDATE bot_publish_request SET state='CANCELLED' WHERE state='PENDING'")
        update("UPDATE bot_outbox SET state='CANCELLED',payload='' WHERE event_key LIKE 'publish:%' AND state='PENDING'")
    }
    @Synchronized fun setMcpEnabled(enabled: Boolean) = transaction {
        update("UPDATE mcp_settings SET enabled=? WHERE id=1", if (enabled) 1 else 0)
        if (!enabled) cancelAgentPublications()
    }
    @Synchronized fun mcpSources(): List<MonitoredChannel> = rows("SELECT channel_id,username,title FROM mcp_sources ORDER BY channel_id") { MonitoredChannel(it.getLong(1), it.getString(2), it.getString(3)) }
    @Synchronized fun allowMcpSource(channel: MonitoredChannel) { require(channel.id < 0 && channel.username.matches(Regex("[a-zA-Z0-9_]{5,32}"))); update("INSERT INTO mcp_sources VALUES(?,?,?) ON CONFLICT(channel_id) DO UPDATE SET username=excluded.username,title=excluded.title", channel.id, channel.username, channel.title) }
    @Synchronized fun revokeMcpSource(id: Long) = transaction {
        update("DELETE FROM mcp_sources WHERE channel_id=?", id)
        cancelAgentPublications()
    }
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
                .filter { it !in allowed }.forEach { update("UPDATE bot_recipients SET active=0 WHERE bot_id=? AND user_id=?", botId, it); update("DELETE FROM bot_workspace WHERE bot_id=? AND user_id=?", botId, it); update("DELETE FROM bot_publish_permission WHERE bot_id=? AND user_id=?", botId, it); update("UPDATE bot_publish_request SET state='CANCELLED' WHERE bot_id=? AND user_id=? AND state='PENDING'", botId, it) }
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
        update("DELETE FROM bot_publish_permission WHERE bot_id=? AND user_id=?", botId, userId)
        update("UPDATE bot_publish_request SET state='CANCELLED' WHERE bot_id=? AND user_id=? AND state='PENDING'", botId, userId)
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
    @Synchronized fun botDeliveryPending(id: Long): Boolean = rows("SELECT 1 FROM bot_outbox WHERE id=? AND state='PENDING'", listOf(id)) { true }.isNotEmpty()
    @Synchronized fun publishPreviewValid(botId: Long, user: Long, request: String, now: Long): Boolean =
        publishAllowed(botId, user) && mcpEnabled() && rows("SELECT 1 FROM bot_publish_request WHERE id=? AND bot_id=? AND user_id=? AND state='PENDING' AND expires_at>?", listOf(request, botId, user, now)) { true }.isNotEmpty()
    @Synchronized fun cancelBotDelivery(id: Long) { update("UPDATE bot_outbox SET state='CANCELLED',payload='' WHERE id=? AND state='PENDING'", id) }
    @Synchronized fun prepareBotDelivery(id: Long, payload: BotPayload) { update("UPDATE bot_outbox SET payload=? WHERE id=? AND state='PENDING'", json.encodeToString(payload), id) }
    @Synchronized fun confirmBotDelivery(id: Long, messageId: Long) { update("UPDATE bot_outbox SET state='SENT',sent_message_id=?,payload='',last_error=NULL WHERE id=? AND state='PENDING'", messageId, id) }
    @Synchronized fun retryBotDelivery(id: Long, at: Long, code: String, permanent: Boolean) {
        update("UPDATE bot_outbox SET state=?,attempts=attempts+1,next_attempt_at=?,last_error=? WHERE id=? AND state='PENDING'", if (permanent) "FAILED" else "PENDING", at, code, id)
    }
    @Synchronized fun botDeliveryCounts(botId: Long): BotDeliveryCounts {
        val counts = rows("SELECT state,COUNT(*) FROM bot_outbox WHERE bot_id=? GROUP BY state", listOf(botId)) { it.getString(1) to it.getInt(2) }.toMap()
        return BotDeliveryCounts(counts["PENDING"] ?: 0, counts["SENT"] ?: 0, counts["FAILED"] ?: 0)
    }

    @Synchronized fun recruitmentSubscribe(id: String, expected: RecruitmentProject, subscriptions: SubscriptionService, rule: SearchTemplate): SearchTemplate = atomic {
        require(project(id) == expected) { "Проект изменился" }
        val template = subscriptions.save(rule)
        writeProject(expected.criteria.copy(templateIds = (expected.criteria.templateIds + template.id).distinct()), id)
        template
    }
    @Synchronized fun project(id: String): RecruitmentProject? = rows("SELECT data FROM recruitment_projects WHERE id=?", listOf(id)) { json.decodeFromString<RecruitmentProject>(it.getString(1)) }.firstOrNull()?.let {
        it.copy(criteria = it.criteria.copy(templateIds = rows("SELECT template_id FROM recruitment_templates WHERE project_id=? ORDER BY template_id", listOf(id)) { r -> r.getString(1) }))
    }
    @Synchronized fun projects(limit: Int, offset: Int, status: ProjectStatus?, query: String): ProjectPage {
        val where = "(? IS NULL OR status=?) AND instr(name,?)>0"
        val args = listOf(status?.name, status?.name, MatchingService().normalize(query))
        val total = rows("SELECT COUNT(*) FROM recruitment_projects WHERE $where", args) { it.getInt(1) }.single()
        val page = rows("SELECT data FROM recruitment_projects WHERE $where ORDER BY updated_at DESC,id LIMIT ? OFFSET ?", args + listOf(limit, offset)) { json.decodeFromString<RecruitmentProject>(it.getString(1)) }
        // Resolve live links in one bounded batch: cascaded template deletion must not
        // leave stale IDs in list DTOs that cannot subsequently be saved.
        val links = if (page.isEmpty()) emptyMap() else rows(
            "SELECT project_id,template_id FROM recruitment_templates WHERE project_id IN (${page.joinToString(",") { "?" }}) ORDER BY template_id",
            page.map { it.id }) { it.getString(1) to it.getString(2) }.groupBy({ it.first }, { it.second })
        return ProjectPage(page.map { it.copy(criteria = it.criteria.copy(templateIds = links[it.id].orEmpty())) }, total)
    }
    @Synchronized fun writeProject(input: ProjectInput, id: String? = null): RecruitmentProject = atomic {
        val old = id?.let { project(it) ?: throw ResourceNotFound() }
        require(input.sources.all { source -> channels().any { it.id == source } }) { "Источник не сохранён" }
        require(input.templateIds.all { template(it) != null }) { "Шаблон не найден" }
        template(recruitmentSubscriptionId(id ?: ""))?.let { owned ->
            val active = input.status == ProjectStatus.ACTIVE
            saveValidatedTemplate(owned.copy(name = input.name, required = input.required, any = emptyList(), excluded = input.excluded, telegramQuery = input.required.firstOrNull() ?: input.name, sources = input.sources, active = active && owned.active), MatchingService(), true)
        }
        val now = System.currentTimeMillis()
        val value = RecruitmentProject(id ?: UUID.randomUUID().toString(), input, old?.createdAt ?: now, now)
        update("INSERT INTO recruitment_projects VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,name=excluded.name,data=excluded.data,updated_at=excluded.updated_at", value.id, input.status.name, MatchingService().normalize(input.name), json.encodeToString(value), value.createdAt, now)
        update("DELETE FROM recruitment_templates WHERE project_id=?", value.id)
        input.templateIds.forEach { update("INSERT INTO recruitment_templates VALUES(?,?)", value.id, it) }
        value
    }
    @Synchronized fun deleteProject(id: String): Boolean = atomic {
        val deleted = update("DELETE FROM recruitment_projects WHERE id=?", id) > 0
        if (deleted) {
            val ownedId = recruitmentSubscriptionId(id)
            val linkedElsewhere = rows("SELECT 1 FROM recruitment_templates WHERE template_id=? LIMIT 1", listOf(ownedId)) { true }.isNotEmpty()
            if (!linkedElsewhere) template(ownedId)?.let { saveTemplate(it.copy(active = false)) }
        }
        deleted
    }
    @Synchronized fun recruitmentCard(projectId: String, id: String): RecruitmentCard? = rows("SELECT data FROM recruitment_cards WHERE project_id=? AND id=?", listOf(projectId, id)) { json.decodeFromString<RecruitmentCard>(it.getString(1)) }.firstOrNull()
    @Synchronized fun addRecruitmentCard(projectId: String, message: ResearchMessage, classification: Classification, expected: RecruitmentProject? = null): RecruitmentCard = atomic {
        val project = project(projectId) ?: throw ResourceNotFound()
        require(expected == null || project == expected) { "Проект изменился. Повторите добавление" }
        require(project.criteria.status == ProjectStatus.ACTIVE) { "Проект архивирован" }
        require(message.channelId in project.criteria.sources && channels().any { it.id == message.channelId }) { "Источник не разрешён в проекте" }
        val now = System.currentTimeMillis()
        val card = RecruitmentCard(UUID.randomUUID().toString(), projectId, message, classification = classification, createdAt = now, updatedAt = now)
        update("INSERT INTO recruitment_cards VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(project_id,channel_id,message_id) DO NOTHING", card.id, projectId, message.channelId, message.id, card.stage.name, 0, MatchingService().normalize(message.text.orEmpty()), "", json.encodeToString(card), now, now)
        rows("SELECT data FROM recruitment_cards WHERE project_id=? AND channel_id=? AND message_id=?", listOf(projectId, message.channelId, message.id)) { json.decodeFromString<RecruitmentCard>(it.getString(1)) }.single()
    }
    @Synchronized fun updateRecruitmentCard(projectId: String, id: String, input: CardUpdate): RecruitmentCard = atomic {
        val project = project(projectId) ?: throw ResourceNotFound()
        require(project.criteria.status == ProjectStatus.ACTIVE) { "Проект архивирован" }
        val old = recruitmentCard(projectId, id) ?: throw ResourceNotFound()
        val card = old.copy(stage = input.stage, notes = input.notes, interest = input.interest, manualKind = input.manualKind, updatedAt = System.currentTimeMillis())
        update("UPDATE recruitment_cards SET stage=?,interest=?,normalized_notes=?,data=?,updated_at=? WHERE project_id=? AND id=?", card.stage.name, card.interest, MatchingService().normalize(card.notes), json.encodeToString(card), card.updatedAt, projectId, id)
        card
    }
    @Synchronized fun deleteRecruitmentCard(projectId: String, id: String): Boolean = atomic {
        val p = project(projectId) ?: throw ResourceNotFound()
        require(p.criteria.status == ProjectStatus.ACTIVE) { "Проект архивирован" }
        update("DELETE FROM recruitment_cards WHERE project_id=? AND id=?", projectId, id) > 0
    }
    @Synchronized fun recruitmentCards(projectId: String, limit: Int, offset: Int, stage: RecruitmentStage?, source: Long?, query: String, technology: String, sort: String): CardPage {
        project(projectId) ?: throw ResourceNotFound()
        val where = "project_id=? AND (? IS NULL OR stage=?) AND (? IS NULL OR channel_id=?) AND instr(normalized_text || ' ' || normalized_notes,?)>0 AND instr(normalized_text,?)>0"
        val args = listOf(projectId, stage?.name, stage?.name, source, source, MatchingService().normalize(query), MatchingService().normalize(technology))
        val total = rows("SELECT COUNT(*) FROM recruitment_cards WHERE $where", args) { it.getInt(1) }.single()
        val order = when(sort) { "interest" -> "interest DESC,updated_at DESC"; "oldest" -> "created_at ASC"; else -> "updated_at DESC" }
        val cards = rows("SELECT data FROM recruitment_cards WHERE $where ORDER BY $order,id LIMIT ? OFFSET ?", args + listOf(limit, offset)) { json.decodeFromString<RecruitmentCard>(it.getString(1)) }
        return CardPage(cards, total)
    }
    @Synchronized fun recruitmentMatches(projectId: String, sources: List<Long>, limit: Int, before: Long): ProjectMatchesPage {
        if (sources.isEmpty()) return ProjectMatchesPage(emptyList())
        val scope = sources.joinToString(",") { "?" }
        // Deduplicate before applying the cursor: otherwise an older notification for
        // another linked template resurfaces the same publication on a later page.
        val page = rows("""WITH publications AS (
            SELECT MAX(n.rowid) AS sequence FROM notifications n
            JOIN matched_messages m ON m.id=n.id JOIN recruitment_templates t ON t.template_id=m.template_id
            WHERE t.project_id=? AND m.channel_id IN ($scope) GROUP BY m.channel_id,m.message_id
        ) SELECT m.data,p.sequence FROM publications p JOIN notifications n ON n.rowid=p.sequence
          JOIN matched_messages m ON m.id=n.id WHERE p.sequence<? ORDER BY p.sequence DESC LIMIT ?""",
            listOf(projectId) + sources + listOf(before, limit + 1)) { json.decodeFromString<NotificationRecord>(it.getString(1)).message to it.getLong(2) }
        return ProjectMatchesPage(page.take(limit).map { it.first }, page.take(limit).lastOrNull()?.second?.takeIf { page.size > limit })
    }
    @Synchronized fun recruitmentStatistics(projectId: String): ProjectStatistics {
        project(projectId) ?: throw ResourceNotFound()
        val counts = rows("SELECT stage,COUNT(*) FROM recruitment_cards WHERE project_id=? GROUP BY stage", listOf(projectId)) { RecruitmentStage.valueOf(it.getString(1)) to it.getInt(2) }.toMap()
        return ProjectStatistics(counts.values.sum(), RecruitmentStage.entries.associateWith { counts[it] ?: 0 })
    }
    @Synchronized fun rememberRecruitmentResults(project: RecruitmentProject, messages: List<ResearchMessage>) = atomic {
        require(project(project.id) == project) { "Критерии изменились" }
        require(messages.size <= 10 && messages.all { it.channelId in project.criteria.sources && it.id > 0 })
        messages.forEach { update("INSERT INTO recruitment_publications VALUES(?,?,?) ON CONFLICT(channel_id,message_id) DO UPDATE SET data=excluded.data", it.channelId, it.id, json.encodeToString(it)) }
    }
    /** Resolve only existing local evidence, never accept user-supplied publication text. */
    @Synchronized fun recruitmentPublication(channelId: Long, messageId: Long): ResearchMessage? {
        val channel = channels().find { it.id == channelId } ?: return null
        val archived = rows("SELECT date,text FROM imported_messages WHERE channel_id=? AND message_id=?", listOf(channelId, messageId)) { ResearchMessage(messageId, channelId, it.getInt(1), it.getString(2), channel.title, channel.username) }.firstOrNull()
        val saved = rows("SELECT data FROM publication_decisions WHERE channel_id=? AND message_id=? ORDER BY sequence DESC LIMIT 1", listOf(channelId, messageId)) { json.decodeFromString<ResearchMessage>(it.getString(1)) }.firstOrNull()
        val matched = rows("SELECT data FROM matched_messages WHERE channel_id=? AND message_id=? LIMIT 1", listOf(channelId, messageId)) { json.decodeFromString<NotificationRecord>(it.getString(1)).message }.firstOrNull()
        val found = rows("SELECT data FROM recruitment_publications WHERE channel_id=? AND message_id=?", listOf(channelId, messageId)) { json.decodeFromString<ResearchMessage>(it.getString(1)) }.firstOrNull()
        return archived?.copy(link = saved?.link ?: matched?.link ?: found?.link) ?: saved ?: matched ?: found
    }
    @Synchronized override fun close() { db.close() }
}
