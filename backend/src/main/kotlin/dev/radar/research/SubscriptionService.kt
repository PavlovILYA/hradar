package dev.radar.research

/** Shared REST/bot subscription operations. This operator surface does not change MCP permissions. */
class SubscriptionService(private val repo: Repositories, private val matching: MatchingService) {
    fun list() = repo.templates()
    fun save(template: SearchTemplate, mustExist: Boolean = false): SearchTemplate = repo.saveValidatedTemplate(template, matching, mustExist)
    fun delete(id: String) = repo.deleteTemplate(id)
    fun setActive(id: String, active: Boolean): SearchTemplate = repo.setSubscriptionActive(id, active, matching)
}
