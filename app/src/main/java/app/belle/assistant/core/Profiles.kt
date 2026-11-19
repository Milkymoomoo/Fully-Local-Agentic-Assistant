package app.belle.assistant.core

/** One assistant profile as far as wake-word routing cares. */
data class ProfileWake(val id: String, val name: String, val wakePhrases: List<String>)

/**
 * Decides which assistant answers a detected wake phrase. Only ever ONE.
 *  - Normal mode: only the active assistant listens; other profiles' phrases are ignored.
 *  - "Each assistant answers its own wake words" mode: the phrase picks the profile; if several
 *    profiles share it, the active one wins, then the user's priority order, then list order.
 */
object ProfileRouter {
    fun norm(s: String) = s.lowercase().replace('_', ' ').replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        .replace(Regex("^okay "), "ok ")

    /** Phrases the wake-word engine should listen for. */
    fun listeningPhrases(profiles: List<ProfileWake>, activeId: String, multi: Boolean): List<String> =
        (if (multi) profiles else profiles.filter { it.id == activeId }).flatMap { it.wakePhrases }.distinctBy { norm(it) }

    fun resolve(hit: String, profiles: List<ProfileWake>, activeId: String, multi: Boolean, priority: List<String> = emptyList()): String? {
        val h = norm(hit)
        val owners = profiles.filter { p -> p.wakePhrases.any { norm(it) == h } }
        if (!multi) return activeId.takeIf { owners.any { it.id == activeId } }
        if (owners.isEmpty()) return null
        owners.firstOrNull { it.id == activeId }?.let { return it.id }
        val rank = { p: ProfileWake -> priority.indexOf(p.id).let { if (it < 0) Int.MAX_VALUE else it } }
        return owners.sortedWith(compareBy(rank).thenBy { profiles.indexOf(it) }).first().id
    }
}
