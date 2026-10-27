package com.hereliesaz.clappiest

/** The "Later" rule for update prompts. Pure Kotlin so it is unit-testable on the JVM. */
object Snooze {
    /** "Later" silences that update for a while; a different one prompts again. */
    fun isSnoozed(key: String, snoozedKey: String?, snoozedUntil: Long, now: Long): Boolean =
        snoozedKey == key && now < snoozedUntil
}
