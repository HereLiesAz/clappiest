package com.hereliesaz.clappiest

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer version and routes the user to where they installed from.
 *
 * Every merge to main publishes the same version to GitHub and Google Play, so one source answers
 * for both. Play can lag GitHub by its review time, hence the wording in the popup.
 *
 * The only network request the app makes: an HTTPS GET to the GitHub API, at most every
 * [CHECK_INTERVAL_MS], only when the app is opened and the user has not turned it off. It sends no
 * identifier beyond a User-Agent naming the app and its version.
 */
class UpdateChecker(private val context: Context, private val prefs: Prefs) {

    data class Update(val version: String, val url: String)

    /** The installed `versionName`, or "" if it cannot be read. */
    val installedVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()

    /**
     * Blocking: call off the main thread. Returns the update to offer, or null when there is none,
     * the check is off or snoozed, or anything fails.
     */
    fun check(now: Long = System.currentTimeMillis()): Update? {
        if (!prefs.updateCheck) return null
        if (now - prefs.lastUpdateCheck >= CHECK_INTERVAL_MS) fetchLatest()?.let {
            prefs.latestVersion = it.version
            prefs.latestUrl = it.url
            prefs.lastUpdateCheck = now
        }
        val version = prefs.latestVersion
        if (!Versions.shouldPrompt(version, installedVersion, prefs.snoozedVersion, prefs.snoozedUntil, now)) {
            return null
        }
        return Update(version!!, prefs.latestUrl ?: RELEASES_URL)
    }

    /** "Later": do not ask about [version] again for [SNOOZE_MS]. */
    fun snooze(version: String, now: Long = System.currentTimeMillis()) {
        prefs.snoozedVersion = version
        prefs.snoozedUntil = now + SNOOZE_MS
    }

    private fun fetchLatest(): Update? {
        val c = runCatching { URL(API_URL).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Clappiest/$installedVersion (Android)")
            if (c.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            if (Versions.parse(tag) == null) return null
            val url = json.optString("html_url").takeIf { it.startsWith(RELEASES_PREFIX) } ?: RELEASES_URL
            Update(tag.trimStart('v', 'V'), url)
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val PLAY_PACKAGE = "com.android.vending"
        private const val API_URL = "https://api.github.com/repos/HereLiesAz/clappiest/releases/latest"
        private const val RELEASES_PREFIX = "https://github.com/HereLiesAz/clappiest/"
        private const val RELEASES_URL = "https://github.com/HereLiesAz/clappiest/releases/latest"
        private const val TIMEOUT_MS = 8_000
        const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        const val SNOOZE_MS = 3 * 24 * 60 * 60 * 1000L

        /** True when Google Play installed this app. Anything else is treated as GitHub. */
        fun installedFromPlay(context: Context): Boolean {
            val pm = context.packageManager
            val installer = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(context.packageName)
                }
            }.getOrNull()
            return installer == PLAY_PACKAGE
        }

        /** Opens the Play listing or the GitHub release; the first target that exists wins. */
        fun open(context: Context, update: Update, fromPlay: Boolean) {
            val targets = if (fromPlay) {
                listOf(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))
                        .setPackage(PLAY_PACKAGE),
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}"),
                    ),
                )
            } else {
                listOf(Intent(Intent.ACTION_VIEW, Uri.parse(update.url)))
            }
            for (intent in targets) {
                if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
            }
        }
    }
}
