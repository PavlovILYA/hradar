package dev.radar.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.security.MessageDigest
import java.util.UUID

/** Separate forward-only schema; never touches research.sqlite. All transactions are short. */
class AgentJobRepository(path: Path) : AutoCloseable {
    private val json = Json { encodeDefaults = true }
    private val db = run {
        Files.createDirectories(path.toAbsolutePath().parent)
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
    }
    init {
        db.createStatement().use { s ->
            s.execute("PRAGMA busy_timeout=5000")
            val version = s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            require(version <= 1) { "Unsupported agent database version" }
            if (version == 0) atomic {
                s.execute("CREATE TABLE agent_jobs(id TEXT PRIMARY KEY,owner TEXT NOT NULL,event TEXT NOT NULL UNIQUE,state TEXT NOT NULL,created_at INTEGER NOT NULL,payload TEXT NOT NULL)")
                s.execute("CREATE UNIQUE INDEX agent_one_active ON agent_jobs(owner) WHERE state IN ('QUEUED','RUNNING')")
                s.execute("CREATE TABLE agent_consent(owner TEXT PRIMARY KEY,payload TEXT NOT NULL)")
                s.execute("CREATE TABLE agent_access(token_hash TEXT PRIMARY KEY,owner TEXT NOT NULL UNIQUE)")
                s.execute("PRAGMA user_version=1")
            }
        }
        runCatching { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
    }
    private fun key(owner: AgentOwner) = "${owner.botId}:${owner.userId}"
    private fun execute(sql: String, vararg args: Any?) = db.prepareStatement(sql).use { s -> args.forEachIndexed { i, v -> s.setObject(i + 1, v) }; s.executeUpdate() }
    private fun <T> rows(sql: String, args: List<Any?> = emptyList(), read: (java.sql.ResultSet) -> T): List<T> = db.prepareStatement(sql).use { s ->
        args.forEachIndexed { i, v -> s.setObject(i + 1, v) }; s.executeQuery().use { r -> buildList { while (r.next()) add(read(r)) } }
    }
    private fun <T> atomic(action: () -> T): T {
        db.autoCommit = false
        try { return action().also { db.commit() } } catch (ex: Throwable) { db.rollback(); throw ex } finally { db.autoCommit = true }
    }
    private fun write(job: AgentJob) { execute("UPDATE agent_jobs SET state=?,payload=? WHERE id=?", job.state.name, json.encodeToString(job), job.id) }
    @Synchronized fun job(id: String): AgentJob? = rows("SELECT payload FROM agent_jobs WHERE id=?", listOf(id)) { json.decodeFromString<AgentJob>(it.getString(1)) }.firstOrNull()
    @Synchronized fun history(owner: AgentOwner, before: Long = Long.MAX_VALUE): List<AgentJob> = rows("SELECT payload FROM agent_jobs WHERE owner=? AND created_at<? ORDER BY created_at DESC,id DESC LIMIT 50", listOf(key(owner), before)) { json.decodeFromString<AgentJob>(it.getString(1)) }
    @Synchronized fun active(): List<AgentJob> = rows("SELECT payload FROM agent_jobs WHERE state IN ('QUEUED','RUNNING') ORDER BY created_at,id") { json.decodeFromString<AgentJob>(it.getString(1)) }
    @Synchronized fun pendingResults(): List<AgentJob> = rows("SELECT payload FROM agent_jobs WHERE state IN ('COMPLETED','FAILED','INTERRUPTED') AND json_extract(payload,'$.delivered')=0 ORDER BY created_at DESC LIMIT 100") { json.decodeFromString<AgentJob>(it.getString(1)) }
    @Synchronized fun consent(owner: AgentOwner): AgentConsent? = rows("SELECT payload FROM agent_consent WHERE owner=?", listOf(key(owner))) { json.decodeFromString<AgentConsent>(it.getString(1)) }.firstOrNull()
    @Synchronized fun consent(owner: AgentOwner, value: AgentConsent) { execute("INSERT INTO agent_consent VALUES(?,?) ON CONFLICT(owner) DO UPDATE SET payload=excluded.payload", key(owner), json.encodeToString(value)) }
    @Synchronized fun create(owner: AgentOwner, event: String, question: String, sources: List<Long>, policy: String, queueSize: Int, now: Long, limits: AgentLimits? = null): AgentJob = atomic {
        rows("SELECT payload FROM agent_jobs WHERE event=?", listOf(event)) { json.decodeFromString<AgentJob>(it.getString(1)) }.firstOrNull()?.let { return@atomic it }
        val active = active()
        if (active.any { it.owner == owner }) throw AgentFailure("ALREADY_ACTIVE")
        if (active.count { it.state == AgentState.QUEUED } >= queueSize) throw AgentFailure("QUEUE_FULL")
        // Unique monotonic pagination boundary even for simultaneous requests in the same millisecond.
        val createdAt = maxOf(now, rows("SELECT COALESCE(MAX(created_at),0) FROM agent_jobs") { it.getLong(1) }.single() + 1)
        val job = AgentJob(UUID.randomUUID().toString(), owner, question, sources, policy, AgentState.QUEUED, createdAt, now, limits = limits)
        execute("INSERT INTO agent_jobs VALUES(?,?,?,?,?,?)", job.id, key(owner), event, job.state.name, createdAt, json.encodeToString(job))
        job
    }
    @Synchronized fun transition(id: String, from: Set<AgentState>, to: AgentState, now: Long, result: AgentResult? = null, error: String? = null): AgentJob? = atomic {
        val job = job(id)?.takeIf { it.state in from } ?: return@atomic null
        job.copy(state = to, updatedAt = now, result = result, error = error).also(::write)
    }
    @Synchronized fun markDelivered(id: String) { job(id)?.let { write(it.copy(delivered = true)) } }
    @Synchronized fun recover(now: Long) { active().forEach { write(it.copy(state = AgentState.INTERRUPTED, updatedAt = now, error = "RESTART_INTERRUPTED")) } }
    @Synchronized fun purge(before: Long) { execute("DELETE FROM agent_jobs WHERE created_at<? AND state NOT IN ('QUEUED','RUNNING')", before) }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    @Synchronized fun access(owner: AgentOwner): String {
        val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        execute("INSERT INTO agent_access VALUES(?,?) ON CONFLICT(owner) DO UPDATE SET token_hash=excluded.token_hash", hash(token), key(owner))
        return token
    }
    @Synchronized fun owner(token: String): AgentOwner? = rows("SELECT owner FROM agent_access WHERE token_hash=?", listOf(hash(token))) { it.getString(1).split(':').let { parts -> AgentOwner(parts[0].toLong(), parts[1].toLong()) } }.firstOrNull()
    @Synchronized override fun close() { db.close() }
}
