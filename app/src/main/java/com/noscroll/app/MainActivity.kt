package com.noscroll.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_app_info).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        // The "Allow restricted settings" step only exists on Android 13+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            findViewById<View>(R.id.restricted_help).visibility = View.GONE
        }
        findViewById<Button>(R.id.btn_dns).setOnClickListener { setUpPrivateDns() }

        buildFeedRules()
        buildSettings()
        buildAdultSettings()
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        val status = findViewById<TextView>(R.id.status)
        status.text = if (on) "● NoScroll is ON" else "● NoScroll is OFF"
        status.setTextColor(getColor(if (on) R.color.ok else R.color.warn))
        findViewById<View>(R.id.setup_card).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.stats).text =
            "Blocked today: ${prefs.todayBlocked}   ·   All time: ${prefs.totalBlocked}"
        refreshUsage()
    }

    // ---- Screen time ---------------------------------------------------------

    private fun refreshUsage() {
        val total = prefs.totalUsageMs()
        findViewById<TextView>(R.id.usage_total).text = formatDuration(total)

        val limit = prefs.dailyLimitMin
        val week = (1L..7L).map { prefs.totalUsageMs(prefs.dayString(it)) }
        val daysWithData = week.count { it > 0 }
        val avg = if (daysWithData > 0) week.sum() / daysWithData else 0L
        val parts = mutableListOf<String>()
        parts += if (limit > 0) "of ${formatDuration(limit * 60_000L)} limit" else "no daily limit"
        parts += "${prefs.totalOpens()} opens"
        if (daysWithData > 0) parts += "avg ${formatDuration(avg)}/day"
        findViewById<TextView>(R.id.usage_sub).text = parts.joinToString("  ·  ")

        val list = findViewById<LinearLayout>(R.id.usage_list)
        list.removeAllViews()
        val byApp = TrackedApps.names.entries
            .groupBy({ it.value }, { it.key })
            .mapValues { (_, pkgs) -> pkgs.sumOf { prefs.usageMs(it) } }
            .filter { it.value >= 60_000L }
            .entries.sortedByDescending { it.value }
        if (byApp.isEmpty()) {
            list.addView(smallText("Nothing yet today — nice."))
        }
        for ((name, ms) in byApp) {
            list.addView(smallText("$name   ${formatDuration(ms)}"))
        }
    }

    private fun smallText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(getColor(R.color.text))
        setPadding(0, dp(4), 0, dp(4))
    }

    // ---- Settings ------------------------------------------------------------

    private fun buildFeedRules() {
        val list = findViewById<LinearLayout>(R.id.rule_list)
        for (rule in Rules.all) {
            val subtitle = if (isInstalled(rule)) rule.subtitle else "${rule.subtitle} · not installed"
            list.addView(switchRow(rule.title, subtitle, prefs.isEnabled(rule)) {
                prefs.setEnabled(rule, it)
            })
        }
    }

    private fun buildSettings() {
        val list = findViewById<LinearLayout>(R.id.settings_list)
        list.addView(choiceRow(
            "Daily limit", "Stop screen when you've used social apps this long",
            listOf(0, 15, 30, 45, 60, 90, 120, 180),
            { prefs.dailyLimitMin }, { prefs.dailyLimitMin = it; refreshUsage() },
        ))
        list.addView(choiceRow(
            "Break reminder", "Nudge after this much non-stop scrolling",
            listOf(0, 5, 10, 15, 20, 30, 45, 60),
            { prefs.breakReminderMin }, { prefs.breakReminderMin = it },
        ))
        list.addView(switchRow(
            "Take-a-breath pause",
            "5-second pause each time you open a social app",
            prefs.mindfulPause,
        ) { prefs.mindfulPause = it })
    }

    private fun buildAdultSettings() {
        findViewById<LinearLayout>(R.id.adult_list).addView(switchRow(
            "Block adult websites",
            "Chrome, Samsung Internet, Firefox, Edge, Brave, Opera, DuckDuckGo…",
            prefs.blockAdult,
        ) { prefs.blockAdult = it })
    }

    private fun setUpPrivateDns() {
        val host = "adult-filter-dns.cleanbrowsing.org"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Private DNS", host))
        Toast.makeText(this, "Copied $host", Toast.LENGTH_LONG).show()
        // There's no public shortcut straight to the Private DNS screen.
        val intents = listOf(
            Intent("android.settings.PRIVATE_DNS_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // try the next one
            }
        }
    }

    private fun switchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = layoutInflater.inflate(R.layout.row_rule, null, false)
        row.findViewById<TextView>(R.id.rule_title).text = title
        row.findViewById<TextView>(R.id.rule_subtitle).text = subtitle
        val sw = row.findViewById<Switch>(R.id.rule_switch)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        row.setOnClickListener { sw.toggle() }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return row
    }

    private fun choiceRow(
        title: String,
        subtitle: String,
        options: List<Int>,
        get: () -> Int,
        set: (Int) -> Unit,
    ): View {
        val row = layoutInflater.inflate(R.layout.row_rule, null, false)
        row.findViewById<Switch>(R.id.rule_switch).visibility = View.GONE
        val titleView = row.findViewById<TextView>(R.id.rule_title)
        row.findViewById<TextView>(R.id.rule_subtitle).text = subtitle
        fun label(min: Int) = if (min == 0) "Off" else formatDuration(min * 60_000L)
        fun refresh() { titleView.text = "$title:  ${label(get())}" }
        refresh()
        row.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(
                    options.map { label(it) }.toTypedArray(),
                    options.indexOf(get()),
                ) { dialog, which ->
                    set(options[which])
                    refresh()
                    dialog.dismiss()
                }
                .show()
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return row
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun isInstalled(rule: AppRule) = rule.packages.any {
        try {
            packageManager.getPackageInfo(it, 0); true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, BlockerService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { ComponentName.unflattenFromString(it) == expected }
    }
}
