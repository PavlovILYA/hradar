package dev.radar.research

class RecruitmentProjectService(private val repo: Repositories, private val matching: MatchingService, private val subscriptions: SubscriptionService, private val recruiter: RecruiterService) {
    private val classifier = PublicationClassifier(matching)
    private fun page(limit: Int, offset: Int) { require(limit in 1..50 && offset in 0..1_000_000) { "Некорректная страница" } }
    fun get(id: String) = repo.project(id) ?: throw ResourceNotFound()
    fun list(limit: Int = 25, offset: Int = 0, status: ProjectStatus? = null, query: String = ""): ProjectPage {
        page(limit, offset); require(query.length <= 256); return repo.projects(limit, offset, status, query)
    }
    fun save(input: ProjectInput, id: String? = null): RecruitmentProject {
        require(input.name.trim().length in 1..100 && input.description.length <= 20000) { "Название: 1–100 символов; описание: до 20000" }
        require(input.sources.distinct().size == input.sources.size && input.sources.size in 1..10) { "Выберите от 1 до 10 источников" }
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
        get(id); require(input.channelId < 0 && input.messageId > 0) { "Некорректная публикация" }
        val message = repo.recruitmentPublication(input.channelId, input.messageId) ?: throw ResourceNotFound()
        return repo.addRecruitmentCard(id, message, classifier.classify(message.text))
    }
    fun update(id: String, card: String, input: CardUpdate): RecruitmentCard {
        require(input.notes.length <= 10000 && input.interest in 0..5) { "Заметка: до 10000 символов; интерес: 0–5" }
        return repo.updateRecruitmentCard(id, card, input)
    }
    fun remove(id: String, card: String) { get(id); if (!repo.deleteRecruitmentCard(id, card)) throw ResourceNotFound() }
    private fun rule(project: RecruitmentProject) = SearchTemplate(name = project.criteria.name, required = project.criteria.required, excluded = project.criteria.excluded, telegramQuery = project.criteria.required.firstOrNull() ?: project.criteria.name, sourceMode = "selected", sources = project.criteria.sources)
    suspend fun search(id: String, request: ProjectSearch): SearchPage {
        val project = get(id)
        val results = recruiter.find(request.query, request.mode, project.criteria.sources, request.cursor)
        require(get(id) == project) { "Критерии изменились; начните поиск заново" }
        repo.rememberRecruitmentResults(project, results.messages)
        return results.copy(messages = results.messages.map { it.copy(explanation = matching.check(rule(project), it.text).copy(foundAny = matching.check(rule(project).copy(any = project.criteria.desired), it.text).foundAny)) }.filter { it.explanation?.matches == true })
    }
    /** Explicitly requested subscription; desired terms never become mandatory OR conditions. */
    fun subscribe(id: String): SearchTemplate {
        val project = get(id); require(project.criteria.status == ProjectStatus.ACTIVE) { "Проект архивирован" }
        return repo.recruitmentSubscribe(id, project, subscriptions, rule(project).copy(id = recruitmentSubscriptionId(id), subscribed = true))
    }
}

internal fun recruitmentSubscriptionId(id: String): String = java.util.UUID.nameUUIDFromBytes("recruitment:$id".toByteArray(Charsets.UTF_8)).toString()
