package com.noscroll.app

import android.content.Context
import java.time.LocalDate

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("noscroll", Context.MODE_PRIVATE)

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
