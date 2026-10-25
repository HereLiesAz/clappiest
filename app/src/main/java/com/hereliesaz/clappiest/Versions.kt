package com.hereliesaz.clappiest

/**
 * Version comparison and the "should we bother the user" rule for update prompts.
 * Pure Kotlin so it is unit-testable on the JVM.
 */
object Versions {
    /** Leading dotted integers: "v0.1.8" -> [0, 1, 8]; "0.2.0-beta" -> [0, 2, 0]; null if none. */
    fun parse(text: String): List<Int>? {
        val m = Regex("""^[vV]?(\d+(?:\.\d+)*)""").find(text.trim()) ?: return null
        return m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    }

    /**
     * True when [remote] is a newer MAJOR.MINOR.PATCH than [local]. Only three components are
     * compared: release tags are three-part, and a re-run build within a patch is not an update.
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parse(remote) ?: return false
        val l = parse(local) ?: return false
        for (i in 0 until 3) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    /** Whether to show the update popup now. */
    fun shouldPrompt(
        remote: String?,
        local: String,
        snoozedVersion: String?,
        snoozedUntil: Long,
        now: Long,
    ): Boolean {
        if (remote == null || !isNewer(remote, local)) return false
        // "Later" silences that version for a while; a newer one prompts again.
        return !(snoozedVersion == remote && now < snoozedUntil)
    }
}
