package dev.radar.research

import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import java.util.UUID

class RecruitmentProjectService(private val repo: Repositories, private val matching: MatchingService, private val subscriptions: SubscriptionService, private val recruiter: RecruiterService, private val searchTimeoutMs: Long = 20_000, private val localSearch: LocalProjectSearchService? = null) {
    // Ephemeral, opaque continuations: bounded memory, no user data or migration in cursors.
    private data class Continuation(val project: RecruitmentProject, val query: String, val mode: String,
        val sourceCursor: String, val seen: Set<Pair<Long, Long>>, val expiresAt: Long)
    private val continuations = object : LinkedHashMap<String, Continuation>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Continuation>?) = size > 128
    }
    @Synchronized private fun continuation(token: String): Continuation {
        require(token.length <= 100) { "Начните поиск заново: некорректное продолжение" }
        val now = System.currentTimeMillis()
        continuations.entries.removeIf { it.value.expiresAt <= now }
        return continuations[token] ?: throw IllegalArgumentException("Поиск устарел. Начните поиск заново")
    }
    @Synchronized private fun remember(state: Continuation): String {
        continuations.entries.removeIf { it.value.expiresAt <= System.currentTimeMillis() }
        return UUID.randomUUID().toString().also { continuations[it] = state }
    }
    private val classifier = PublicationClassifier(matching)
    private fun page(limit: Int, offset: Int) { require(limit in 1..50 && offset in 0..1_000_000) { "Некорректная страница" } }
    fun get(id: String) = repo.project(id) ?: throw ResourceNotFound()
    fun list(limit: Int = 25, offset: Int = 0, status: ProjectStatus? = null, query: String = ""): ProjectPage {
        page(limit, offset); require(query.length <= 256); return repo.projects(limit, offset, status, query)
    }
    fun save(input: ProjectInput, id: String? = null): RecruitmentProject {
        require(input.name.trim().length in 1..100 && input.description.length <= 20000) { "Название: 1–100 символов; описание: до 20000" }
        require(input.sources.distinct().size == input.sources.size && input.sources.size + input.sourceKeys.size in 1..10 && input.sourceKeys.distinct().size == input.sourceKeys.size) { "Выберите от 1 до 10 источников" }
        require(input.templateIds.size <= 50 && input.templateIds.distinct().size == input.templateIds.size) { "Некорректные связи шаблонов" }
        fun terms(values: List<String>): List<String> {
            require(values.size <= 20 && values.all { it.trim().length in 1..100 }) { "До 20 требований, каждое 1–100 символов" }
            return values.map(String::trim).distinctBy(matching::normalize)
        }
        val normalized = input.copy(name = input.name.trim(), required = terms(input.required), desired = terms(input.desired), excluded = terms(input.excluded))
        return repo.writeProject(normalized, id)
    }
    fun delete(id: String) { if (!repo.deleteProject(id)) throw ResourceNotFound() }
    fun cards(id: String, limit: Int = 25, offset: Int = 0, stage: RecruitmentStage? = null, source: Long? = null, query: String = "", technology: String = "", sort: String = "updated"): CardPage {
        get(id); page(limit, offset); require(query.length <= 256 && technology.length <= 100 && sort in setOf("updated", "oldest", "interest")) { "Некорректные фильтры" }
        return repo.recruitmentCards(id, limit, offset, stage, source, query, technology, sort)
    }
    fun matches(id: String, limit: Int = 20, before: Long = Long.MAX_VALUE): ProjectMatchesPage {
        val project = get(id); page(limit, 0); require(before > 0) { "Некорректный курсор" }
        val savedSources = repo.channels().map { it.id }.toSet()
        return repo.recruitmentMatches(id, project.criteria.sources.filter { it in savedSources }, limit, before)
    }
    fun statistics(id: String): ProjectStatistics { get(id); return repo.recruitmentStatistics(id) }
    fun add(id: String, input: AddPublication): RecruitmentCard {
        val project = get(id); require(input.channelId != 0L && input.messageId > 0) { "Некорректная публикация" }
        val evidence = (if (input.sourceKey == null && input.channelId in project.criteria.sources)
            repo.recruitmentPublication(input.channelId, input.messageId)
                ?: repo.localProjectPublication(project, input.channelId, input.messageId)
            else repo.localProjectPublication(project, input.channelId, input.messageId, input.sourceKey)) ?: throw ResourceNotFound()
        // Shared publication evidence may carry another project's/template's explanation.
        val message = evidence.copy(explanation = explain(project, evidence.text))
        return repo.addRecruitmentCard(id, message, classifier.classify(message.text), project)
    }
    fun update(id: String, card: String, input: CardUpdate): RecruitmentCard {
        require(input.notes.length <= 10000 && input.interest in 0..5) { "Заметка: до 10000 символов; интерес: 0–5" }
        return repo.updateRecruitmentCard(id, card, input)
    }
    fun remove(id: String, card: String) { get(id); if (!repo.deleteRecruitmentCard(id, card)) throw ResourceNotFound() }
    private fun rule(project: RecruitmentProject) = SearchTemplate(name = project.criteria.name, required = project.criteria.required, excluded = project.criteria.excluded, telegramQuery = project.criteria.required.firstOrNull() ?: project.criteria.name, sourceMode = "selected", sources = project.criteria.sources)
    private fun explain(project: RecruitmentProject, text: String?): MatchExplanation {
        val criteria = rule(project)
        return matching.check(criteria, text).copy(foundAny = matching.check(
            criteria.copy(required = emptyList(), excluded = emptyList(), any = project.criteria.desired), text).foundAny)
    }
    suspend fun search(id: String, request: ProjectSearch): ProjectSearchPage {
        val project = get(id)
        require(request.query.trim().length in 1..256 && request.mode in setOf("telegram", "archive")) { "Введите запрос и выберите место поиска" }
        val previous = request.cursor.takeIf { it.isNotEmpty() }?.let(::continuation)
        require(previous == null || previous.project == project && previous.query == request.query && previous.mode == request.mode) { "Условия изменились. Начните поиск заново" }
        val savedSources = repo.channels().map { it.id }.toSet()
        if (project.criteria.sourceKeys.isEmpty() && (project.criteria.sources.isEmpty() || project.criteria.sources.none { it in savedSources }))
            return ProjectSearchPage(state = "no_sources", notice = "Сохраните Telegram-источники и выберите их в настройках проекта.")
        require(project.criteria.sources.all { it in savedSources }) { "Один из источников удалён. Обновите источники проекта" }
        require(project.criteria.sourceKeys.all { key -> repo.sources().any { it.key == key } }) { "Один из источников удалён. Обновите источники проекта" }
        if (request.mode == "archive" && project.criteria.sourceKeys.isEmpty() && repo.recruitmentArchiveSize(project.criteria.sources) == 0L)
            return ProjectSearchPage(state = "archive_empty", exhausted = true)

        var sourceCursor = previous?.sourceCursor.orEmpty()
        val seen = previous?.seen.orEmpty().toMutableSet()
        val visited = mutableSetOf<String>()
        val messages = mutableListOf<ResearchMessage>()
        var scanned = 0; var pages = 0; var exhausted = false
        var state = "ok"; var notice: String? = null
        val deadline = System.nanoTime() + searchTimeoutMs * 1_000_000
        while (messages.size < 10 && pages < 5 && seen.size < 10_000) {
            coroutineContext.ensureActive()
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) { state = "search_timeout"; break }
            visited.add(sourceCursor)
            val result = try {
                withTimeoutOrNull(remainingMs) {
                    if (project.criteria.sourceKeys.isNotEmpty()) {
                        requireNotNull(localSearch) { "Локальный поиск не подключён" }.find(project.criteria, request.query, request.mode, sourceCursor, minOf(10 - messages.size, 10_000 - seen.size))
                    } else recruiter.find(request.query, request.mode, project.criteria.sources, sourceCursor,
                        minOf(10 - messages.size, 10_000 - seen.size), withLinks = false)
                }
            } catch (e: TelegramRequestException) {
                state = if (e.code == 429 || e.reason.startsWith("FLOOD_WAIT")) "rate_limited" else "telegram_error"
                // No immediate retry, no cursor advancement for an unsuccessful page.
                break
            }
            if (result == null) { state = "search_timeout"; break }
            require(get(id) == project) { "Критерии изменились; начните поиск заново" }
            pages++
            for (message in result.messages) {
                if (!seen.add(message.channelId to message.id)) continue
                scanned++
                val explanation = explain(project, message.text)
                if (explanation.matches) messages.add(message.copy(explanation = explanation))
            }
            sourceCursor = result.cursor
            if (result.state != "ok") {
                state = result.state; notice = result.notice
                // A link rate limit can occur after every source page is complete.
                exhausted = state == "rate_limited" && sourceCursor.isEmpty()
                break
            }
            if (sourceCursor.isEmpty()) { exhausted = true; break }
            if (sourceCursor in visited) { state = "cursor_stalled"; break }
        }
        coroutineContext.ensureActive()
        require(get(id) == project) { "Критерии изменились; начните поиск заново" }
        val limitReached = !exhausted && (pages == 5 && messages.size < 10 || state == "search_timeout" || seen.size >= 10_000)
        if (state == "ok" && limitReached) state = "budget"
        // Persist only actual matching evidence, transactionally bound to the criteria snapshot.
        if (project.criteria.sourceKeys.isNotEmpty()) repo.rememberLocalProjectResults(project, messages)
        else repo.rememberRecruitmentResults(project, messages)
        val cursor = if (exhausted || seen.size >= 10_000) "" else remember(Continuation(project, request.query, request.mode,
            sourceCursor, seen.toSet(), System.currentTimeMillis() + 10 * 60_000))
        return ProjectSearchPage(messages, cursor, state, notice, scanned, messages.size, pages, limitReached, exhausted,
            repo.recruitmentAddedKeys(id, messages))
    }
    /** Explicitly requested subscription; desired terms never become mandatory OR conditions. */
    fun subscribe(id: String): SearchTemplate {
        val project = get(id); require(project.criteria.status == ProjectStatus.ACTIVE) { "Проект архивирован" }
        require(project.criteria.sourceKeys.isEmpty()) { "Мониторинг выбранных локальных источников появится на этапе C" }
        return repo.recruitmentSubscribe(id, project, subscriptions, rule(project).copy(id = recruitmentSubscriptionId(id), subscribed = true))
    }
}

internal fun recruitmentSubscriptionId(id: String): String = java.util.UUID.nameUUIDFromBytes("recruitment:$id".toByteArray(Charsets.UTF_8)).toString()
