package dev.radar.research

import kotlinx.serialization.Serializable

@Serializable enum class ProjectStatus { ACTIVE, ARCHIVED }
@Serializable enum class RecruitmentStage { NEW, INTERESTING, CONTACTED, IN_PROGRESS, REJECTED }
@Serializable enum class PublicationKind { CANDIDATE, EMPLOYER, OTHER, INSUFFICIENT }
@Serializable data class Classification(val kind: PublicationKind, val reason: String)
@Serializable data class ProjectInput(val name: String, val description: String = "", val required: List<String> = emptyList(), val desired: List<String> = emptyList(), val excluded: List<String> = emptyList(), val sources: List<Long> = emptyList(), val templateIds: List<String> = emptyList(), val status: ProjectStatus = ProjectStatus.ACTIVE)
@Serializable data class RecruitmentProject(val id: String, val criteria: ProjectInput, val createdAt: Long, val updatedAt: Long)
@Serializable data class ProjectPage(val items: List<RecruitmentProject>, val total: Int)
@Serializable data class RecruitmentCard(val id: String, val projectId: String, val message: ResearchMessage, val stage: RecruitmentStage = RecruitmentStage.NEW, val notes: String = "", val interest: Int = 0, val classification: Classification, val manualKind: PublicationKind? = null, val createdAt: Long, val updatedAt: Long)
@Serializable data class CardUpdate(val stage: RecruitmentStage, val notes: String = "", val interest: Int = 0, val manualKind: PublicationKind? = null)
@Serializable data class AddPublication(val channelId: Long, val messageId: Long)
@Serializable data class CardPage(val items: List<RecruitmentCard>, val total: Int)
@Serializable data class ProjectStatistics(val total: Int, val stages: Map<RecruitmentStage, Int>)
@Serializable data class ProjectMatchesPage(val messages: List<ResearchMessage>, val nextCursor: Long? = null)
@Serializable data class ProjectSearch(val mode: String = "archive", val query: String, val cursor: String = "")

/** Conservative phrase rules. Technology names alone never identify a person's intent. */
class PublicationClassifier(private val matching: MatchingService = MatchingService()) {
    fun classify(text: String?): Classification {
        val value = matching.normalize(text.orEmpty())
        if (value.length < 20) return Classification(PublicationKind.INSUFFICIENT, "Слишком мало текста для определения намерения")
        if (listOf("не ищу работу", "not looking for work", "not looking for a job").any { it in value }) return Classification(PublicationKind.INSUFFICIENT, "Обнаружено отрицание поиска работы; требуется ручная проверка")
        val candidate = listOf("ищу работу", "ищу новую работу", "рассматриваю предложения", "в поиске работы", "looking for work", "looking for a job", "open to work").firstOrNull { it in value }
        val employer = listOf("ищем разработчика", "ищем java", "ищем backend", "приглашаем разработчика", "требуется разработчик", "открыта вакансия", "we are hiring", "we're hiring", "#вакансия", "#vacancy").firstOrNull { it in value }
        if (candidate != null && employer != null) return Classification(PublicationKind.INSUFFICIENT, "Есть признаки и поиска работы, и предложения вакансии")
        if (candidate != null) return Classification(PublicationKind.CANDIDATE, "Признак поиска работы: «$candidate»")
        if (employer != null) return Classification(PublicationKind.EMPLOYER, "Признак предложения работы: «$employer»")
        val other = listOf("вебинар", "курс обучения", "промокод", "webinar", "конференция").firstOrNull { it in value }
        return if (other != null) Classification(PublicationKind.OTHER, "Признак другой публикации: «$other»") else Classification(PublicationKind.INSUFFICIENT, "Явных признаков намерения не найдено")
    }
}
