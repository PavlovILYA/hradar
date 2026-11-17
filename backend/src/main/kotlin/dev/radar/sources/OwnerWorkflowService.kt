package dev.radar.sources

import dev.radar.research.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Local UI workflow. This facade grants no Bot/MCP access and performs no joins/imports. */
class OwnerWorkflowService(
    private val repo: Repositories,
    private val recent: suspend (String, Long, Int) -> SourceMessagePage,
    private val onSaved: () -> Unit = {},
) {
    private val mutations = Mutex()
    suspend fun save(input: SearchTemplate): SearchTemplate = mutations.withLock {
        val validated = MatchingService().validate(input)
        val previous = withContext(Dispatchers.IO) { repo.template(input.id) }
        require(previous != null || validated.sourceMode == "chats") { "Выберите чаты для мониторинга" }
        require(input.id.isEmpty() || previous != null) { "Шаблон не найден" }
        val template = validated.copy(id = previous?.id ?: UUID.randomUUID().toString(), sources = if (validated.sourceMode == "chats") emptyList() else validated.sources)
        val observations = mutableListOf<SubscriptionObservation>()
        val enabled = template.active && template.subscribed
        val subscriptions = withContext(Dispatchers.IO) { repo.localSubscriptions().filter { it.templateId == template.id } }
        val keys = if (template.sourceMode == "chats") template.sourceKeys else subscriptions.map { it.sourceKey }.distinct()
        for (key in keys) {
            currentCoroutineContext().ensureActive()
            val source = withContext(Dispatchers.IO) { repo.sources().firstOrNull { it.key == key } } ?: throw ResourceNotFound()
            val old = subscriptions.firstOrNull { it.sourceKey == key }
            val since = (System.currentTimeMillis() / 1000).toInt()
            val head = if (enabled && (old == null || old.sinceDate == 0)) {
                val page = recent(key, 0, 1)
                require(page.state == "ok") { "Не удалось установить начало мониторинга" }
                page.messages.maxOfOrNull { it.id } ?: 0
            } else null
            observations += SubscriptionObservation(source, old, head, since)
        }
        withContext(Dispatchers.IO) { repo.saveOwnerTemplate(template, previous, subscriptions, observations).also { onSaved() } }
    }
}

data class SubscriptionObservation(val source: TelegramSource, val previous: LocalSubscription?, val head: Long?, val since: Int)
