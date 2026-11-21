package com.noscroll.app

import android.content.Context
import java.time.LocalDate

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("noscroll", Context.MODE_PRIVATE)

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

    private fun today() = LocalDate.now().toString()
}
