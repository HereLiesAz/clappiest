package com.hereliesaz.clappiest

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import java.util.concurrent.TimeUnit

/**
 * Asks Google Play whether a newer version exists, and decides when to bother the user.
 *
 * The question goes to the Play Store app on the phone, which talks to Google. This app makes no
 * network request of its own and has no INTERNET permission. Every merge to main publishes to
 * Google Play and GitHub together, so an update on Play means one on GitHub too.
 *
 * Play answers only for copies it installed ("owned" by the user's Google account). For any other
 * copy the question fails and the user simply sees no popup.
 *
 * At most one answer every [CHECK_INTERVAL_MS], only when the app is opened and the user has not
 * turned it off. A failed check does not count, so it is retried at the next launch.
 */
class UpdateChecker(private val context: Context, private val prefs: Prefs) {

    /** An update Google Play has for this device. Play reports a version code, not a name. */
    data class Update(val versionCode: Int)

    /** Blocking: call off the main thread. The update to offer now, or null. */
    fun check(now: Long = System.currentTimeMillis()): Update? {
        if (!prefs.updateCheck) return null
        if (now - prefs.lastUpdateCheck < CHECK_INTERVAL_MS) return null
        val info = try {
            Tasks.await(AppUpdateManagerFactory.create(context).appUpdateInfo, TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return null // Not installed by Play, Play unavailable, or timed out.
        }
        prefs.lastUpdateCheck = now
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return null
        val update = Update(info.availableVersionCode())
        val snoozed = Snooze.isSnoozed(update.versionCode.toString(), prefs.snoozedKey, prefs.snoozedUntil, now)
        return if (snoozed) null else update
    }

    /** "Later": do not ask about [update] again for [SNOOZE_MS]. */
    fun snooze(update: Update, now: Long = System.currentTimeMillis()) {
        prefs.snoozedKey = update.versionCode.toString()
        prefs.snoozedUntil = now + SNOOZE_MS
    }

    /** Opens the Play Store listing, falling back to the web page. */
    fun open() {
        val targets = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))
                .setPackage(PLAY_PACKAGE),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")),
        )
        for (intent in targets) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    companion object {
        private const val PLAY_PACKAGE = "com.android.vending"
        private const val TIMEOUT_SECONDS = 10L
        const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        const val SNOOZE_MS = 3 * 24 * 60 * 60 * 1000L
    }
}
