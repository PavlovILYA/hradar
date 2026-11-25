package dev.radar.profiles

/** Never release exclusivity or start another client after incomplete teardown. */
internal fun closeProfileResources(steps: List<()->Unit>, release: ()->Unit, fatal: (Throwable)->Nothing) {
    var failure: Throwable? = null
    for (step in steps) try { step() } catch (problem: Throwable) { if (failure == null) failure = problem }
    failure?.let { fatal(it) }
    release()
}
/** Once accepted, a change completes even when the caller disconnects before its response. */
internal suspend fun commitProfileChange(write: suspend ()->Unit, restart: ()->Unit) {
    try { write() } finally { restart() }
}
