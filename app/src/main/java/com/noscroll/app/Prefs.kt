package com.noscroll.app

import android.content.Context
import java.time.LocalDate

class Prefs(private val context: Context) {
    private val sp = context.getSharedPreferences("noscroll", Context.MODE_PRIVATE)

    // ---- Pro -----------------------------------------------------------------

    /** "lifetime", "subscription", or "" (not purchased). Cached from the website. */
    var proKind: String
        get() = sp.getString("pro_kind", "") ?: ""
        set(v) = sp.edit().putString("pro_kind", v).apply()

    /** For subscriptions: when the paid period ends (ms). 0 for lifetime. */
    var proUntil: Long
        get() = sp.getLong("pro_until", 0L)
        set(v) = sp.edit().putLong("pro_until", v).apply()

    /** Website session token for the signed-in Google account. */
    var authToken: String
        get() = sp.getString("auth_token", "") ?: ""
        set(v) = sp.edit().putString("auth_token", v).apply()

    var accountEmail: String
        get() = sp.getString("account_email", "") ?: ""
        set(v) = sp.edit().putString("account_email", v).apply()

    val isPro: Boolean get() = Pro.isActive(context)

    /** Fallback trial start if the install time can't be read. */
    val firstSeen: Long
        get() {
            val v = sp.getLong("first_seen", 0L)
            if (v != 0L) return v
            val now = System.currentTimeMillis()
            sp.edit().putLong("first_seen", now).apply()
            return now
        }

    var trialEndNoticeShown: Boolean
        get() = sp.getBoolean("trial_end_notice", false)
        set(v) = sp.edit().putBoolean("trial_end_notice", v).apply()

    var disclosureAccepted: Boolean
        get() = sp.getBoolean("disclosure_ok", false)
        set(v) = sp.edit().putBoolean("disclosure_ok", v).apply()

    // ---- Effective settings (Pro features switch off when Pro isn't active) ----

    val blockAdultActive get() = blockAdult && isPro
    val dailyLimitActiveMin get() = if (isPro) dailyLimitMin else 0
    val mindfulPauseActive get() = mindfulPause && isPro
    val sleepEnabledActive get() = sleepEnabled && isPro

    // ---- Feed blocking -------------------------------------------------------

    fun isEnabled(rule: AppRule): Boolean = sp.getBoolean("enabled_${rule.key}", true)

    fun setEnabled(rule: AppRule, enabled: Boolean) {
        sp.edit().putBoolean("enabled_${rule.key}", enabled).apply()
    }

    val totalBlocked: Int get() = sp.getInt("total", 0)

    val todayBlocked: Int
        get() = if (sp.getString("day", null) == today()) sp.getInt("today", 0) else 0

    fun recordBlock() {
        val day = today()
        val todayCount = if (sp.getString("day", null) == day) sp.getInt("today", 0) else 0
        sp.edit()
            .putInt("total", totalBlocked + 1)
            .putString("day", day)
            .putInt("today", todayCount + 1)
            .apply()
    }

    // ---- Adult content -------------------------------------------------------

    var blockAdult: Boolean
        get() = sp.getBoolean("block_adult", true)
        set(v) = sp.edit().putBoolean("block_adult", v).apply()

    // ---- Screen time settings -----------------------------------------------

    /** Daily social media limit in minutes; 0 = no limit. */
    var dailyLimitMin: Int
        get() = sp.getInt("daily_limit", 60)
        set(v) = sp.edit().putInt("daily_limit", v).apply()

    /** Remind after this many minutes of continuous scrolling; 0 = off. */
    var breakReminderMin: Int
        get() = sp.getInt("break_min", 15)
        set(v) = sp.edit().putInt("break_min", v).apply()

    /** Show a short "take a breath" screen when opening a social app. */
    var mindfulPause: Boolean
        get() = sp.getBoolean("mindful_pause", true)
        set(v) = sp.edit().putBoolean("mindful_pause", v).apply()

    /** Wall-clock time until which the daily limit is snoozed. */
    var limitSnoozeUntil: Long
        get() = sp.getLong("limit_snooze", 0L)
        set(v) = sp.edit().putLong("limit_snooze", v).apply()

    /** Seconds the take-a-breath pause lasts before the app can be opened. */
    var pauseSeconds: Int
        get() = sp.getInt("pause_seconds", 5)
        set(v) = sp.edit().putInt("pause_seconds", v).apply()

    /** Whether the daily-limit screen offers "5 more minutes". */
    var allowLimitSnooze: Boolean
        get() = sp.getBoolean("allow_limit_snooze", true)
        set(v) = sp.edit().putBoolean("allow_limit_snooze", v).apply()

    // ---- Sleep mode ----------------------------------------------------------

    var sleepEnabled: Boolean
        get() = sp.getBoolean("sleep_enabled", false)
        set(v) = sp.edit().putBoolean("sleep_enabled", v).apply()

    /** Minutes after midnight. */
    var sleepStart: Int
        get() = sp.getInt("sleep_start", 22 * 60)
        set(v) = sp.edit().putInt("sleep_start", v).apply()

    var sleepEnd: Int
        get() = sp.getInt("sleep_end", 7 * 60)
        set(v) = sp.edit().putInt("sleep_end", v).apply()

    /** Nights sleep mode starts on, as a bitmask: bit 0 = Monday … bit 6 = Sunday. */
    var sleepDays: Int
        get() = sp.getInt("sleep_days", 0b1111111)
        set(v) = sp.edit().putInt("sleep_days", v).apply()

    /** App names (from [TrackedApps]) blocked during sleep. */
    var sleepApps: Set<String>
        get() = sp.getStringSet("sleep_apps", null) ?: TrackedApps.names.values.toSet()
        set(v) = sp.edit().putStringSet("sleep_apps", v).apply()

    var sleepBlockBrowsers: Boolean
        get() = sp.getBoolean("sleep_browsers", true)
        set(v) = sp.edit().putBoolean("sleep_browsers", v).apply()

    var sleepAllowUnlock: Boolean
        get() = sp.getBoolean("sleep_allow_unlock", false)
        set(v) = sp.edit().putBoolean("sleep_allow_unlock", v).apply()

    var sleepUnlockUntil: Long
        get() = sp.getLong("sleep_unlock_until", 0L)
        set(v) = sp.edit().putLong("sleep_unlock_until", v).apply()

    /** Stops NoScroll from being turned off or changed while sleep mode is active. */
    var sleepStrict: Boolean
        get() = sp.getBoolean("sleep_strict", false)
        set(v) = sp.edit().putBoolean("sleep_strict", v).apply()

    // ---- Ad blocker ----------------------------------------------------------

    var adBlockEnabled: Boolean
        get() = sp.getBoolean("adblock", false)
        set(v) = sp.edit().putBoolean("adblock", v).apply()

    /** Upstream DNS used by the ad blocker: "adult" (CleanBrowsing adult filter) or "plain". */
    var adBlockUpstream: String
        get() = sp.getString("adblock_upstream", "adult") ?: "adult"
        set(v) = sp.edit().putString("adblock_upstream", v).apply()

    // ---- Usage data ----------------------------------------------------------

    fun addUsage(pkg: String, ms: Long, day: String = today()) {
        if (ms <= 0) return
        val key = "use_${day}_$pkg"
        val dayKey = "usetotal_$day"
        sp.edit()
            .putLong(key, sp.getLong(key, 0L) + ms)
            .putLong(dayKey, sp.getLong(dayKey, 0L) + ms)
            .apply()
    }

    fun usageMs(pkg: String, day: String = today()) = sp.getLong("use_${day}_$pkg", 0L)

    fun totalUsageMs(day: String = today()) = sp.getLong("usetotal_$day", 0L)

    fun recordOpen(pkg: String) {
        val key = "opens_${today()}_$pkg"
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply()
    }

    fun opens(pkg: String, day: String = today()) = sp.getInt("opens_${day}_$pkg", 0)

    fun totalOpens(day: String = today()) =
        TrackedApps.names.keys.sumOf { opens(it, day) }

    fun dayString(daysAgo: Long) = LocalDate.now().minusDays(daysAgo).toString()

    private fun today() = LocalDate.now().toString()
}

fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}
