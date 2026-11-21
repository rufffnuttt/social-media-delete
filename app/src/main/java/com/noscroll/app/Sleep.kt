package com.noscroll.app

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Works out whether sleep mode is active right now. */
object Sleep {

    val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** True if the sleep schedule covers this moment (ignores the emergency unlock). */
    fun inSchedule(prefs: Prefs, now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (!prefs.sleepEnabled) return false
        val start = prefs.sleepStart
        val end = prefs.sleepEnd
        val t = now.hour * 60 + now.minute
        val today = now.dayOfWeek.value - 1          // 0 = Monday
        val yesterday = (today + 6) % 7
        fun nightOn(day: Int) = (prefs.sleepDays and (1 shl day)) != 0

        return if (start == end) {
            nightOn(today)                            // all day
        } else if (start < end) {
            nightOn(today) && t >= start && t < end   // e.g. 13:00–15:00
        } else {
            // Overnight, e.g. 22:00–07:00: tonight's part or the tail of last night.
            (nightOn(today) && t >= start) || (nightOn(yesterday) && t < end)
        }
    }

    /** True if sleep mode is blocking things right now. */
    fun isActive(prefs: Prefs): Boolean =
        inSchedule(prefs) && System.currentTimeMillis() >= prefs.sleepUnlockUntil

    /** Strict lock: sleep is on and the user asked us to prevent turning NoScroll off. */
    fun isLocked(prefs: Prefs): Boolean = prefs.sleepStrict && inSchedule(prefs)

    fun blocksApp(prefs: Prefs, pkg: String): Boolean =
        isActive(prefs) && TrackedApps.isTracked(pkg) && TrackedApps.name(pkg) in prefs.sleepApps

    fun formatTime(minutes: Int): String {
        val time = java.time.LocalTime.of(minutes / 60, minutes % 60)
        return time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    }

    fun describeDays(mask: Int): String = when (mask and 0b1111111) {
        0b1111111 -> "every night"
        0b0011111 -> "Mon–Fri nights"
        0b1100000 -> "Sat & Sun nights"
        0b1001111 -> "school nights (Sun–Thu)"
        0 -> "no nights"
        else -> dayNames.filterIndexed { i, _ -> (mask and (1 shl i)) != 0 }.joinToString(", ")
    }
}
