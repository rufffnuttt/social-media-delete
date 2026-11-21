package com.noscroll.app

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
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

        findViewById<Button>(R.id.btn_enable).setOnClickListener { openAccessibilityWithDisclosure() }
        findViewById<Button>(R.id.btn_pro).setOnClickListener { openPro() }
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
        buildSleepSettings()
        buildSettings()
        buildAdultSettings()
        buildAdBlockSettings()

        // Keep the ad blocker running if it was on (e.g. after the app was updated).
        if (prefs.adBlockEnabled && prefs.isPro && !AdBlockFeature.running &&
            AdBlockFeature.consentIntent(this) == null
        ) {
            AdBlockFeature.start(this)
        }
        // Make sure Pro status matches Google Play (purchases on another device, cancellations…).
        Store.refresh(this) { refreshStatus() }
    }

    private fun openPro() = startActivity(Intent(this, ProActivity::class.java))

    /**
     * Google Play requires a clear explanation, and the user's agreement, before an app
     * that isn't an accessibility tool asks for the Accessibility permission.
     */
    private fun openAccessibilityWithDisclosure() {
        if (prefs.disclosureAccepted) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("How NoScroll uses Accessibility")
            .setMessage(
                "NoScroll uses Android's Accessibility service to:\n\n" +
                    "• see when Reels, Shorts, Spotlight or TikTok are on screen, and press Back to close them\n" +
                    "• read your browser's address bar to block adult websites\n" +
                    "• see which app is open, to count social media time, show reminders and enforce sleep mode\n\n" +
                    "NoScroll does not collect, store or share any of what's on your screen. " +
                    "Nothing leaves your phone."
            )
            .setPositiveButton("Agree") { _, _ ->
                prefs.disclosureAccepted = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("No thanks", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val email = prefs.accountEmail
        findViewById<TextView>(R.id.pro_line).text =
            Pro.statusLine(this) + if (Store.IS_STORE_BUILD && email.isNotEmpty()) "\n$email" else ""
        findViewById<View>(R.id.btn_pro).visibility = if (Pro.isPurchased(this)) View.GONE else View.VISIBLE
        // When the trial ends: switch off the ad blocker and show the upgrade screen once.
        if (!prefs.isPro) {
            if (AdBlockFeature.running) AdBlockFeature.stop(this)
            if (Store.IS_STORE_BUILD && !prefs.trialEndNoticeShown) {
                prefs.trialEndNoticeShown = true
                openPro()
            }
        }
        val on = isServiceEnabled()
        val status = findViewById<TextView>(R.id.status)
        status.text = if (on) "● NoScroll is ON" else "● NoScroll is OFF"
        status.setTextColor(getColor(if (on) R.color.ok else R.color.warn))
        findViewById<View>(R.id.setup_card).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.stats).text =
            "Blocked today: ${prefs.todayBlocked}   ·   All time: ${prefs.totalBlocked}"
        val banner = findViewById<TextView>(R.id.lock_banner)
        when {
            Sleep.isLocked(prefs) -> {
                banner.text = "🌙 Sleep mode is on with strict lock. Settings can't be changed until " +
                    "${Sleep.formatTime(prefs.sleepEnd)}."
                banner.visibility = View.VISIBLE
            }
            Sleep.isActive(prefs) -> {
                banner.text = "🌙 Sleep mode is on until ${Sleep.formatTime(prefs.sleepEnd)}."
                banner.visibility = View.VISIBLE
            }
            else -> banner.visibility = View.GONE
        }
        refreshUsage()
        refreshRows()
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
            pro = true,
        ))
        list.addView(choiceRow(
            "Break reminder", "Nudge after this much non-stop scrolling",
            listOf(0, 5, 10, 15, 20, 30, 45, 60),
            { prefs.breakReminderMin }, { prefs.breakReminderMin = it },
        ))
        list.addView(switchRow(
            "Allow \"5 more minutes\"",
            "Offer a short extension on the daily-limit screen",
            prefs.allowLimitSnooze,
            pro = true,
        ) { prefs.allowLimitSnooze = it })
        list.addView(switchRow(
            "Take-a-breath pause",
            "Short pause each time you open a social app",
            prefs.mindfulPause,
            pro = true,
        ) { prefs.mindfulPause = it })
        list.addView(choiceRow(
            "Pause length", "How long before the app can be opened",
            listOf(3, 5, 10, 15, 30),
            { prefs.pauseSeconds }, { prefs.pauseSeconds = it },
            label = { "$it sec" },
            pro = true,
        ))
    }

    private fun buildSleepSettings() {
        val list = findViewById<LinearLayout>(R.id.sleep_list)
        list.addView(switchRow(
            "Sleep mode",
            "Blocks social media completely during your sleep hours",
            prefs.sleepEnabled,
            pro = true,
        ) { prefs.sleepEnabled = it; refreshStatus() })
        list.addView(actionRow("Starts", { Sleep.formatTime(prefs.sleepStart) }, pro = true) { refresh ->
            pickTime(prefs.sleepStart) { prefs.sleepStart = it; refresh() }
        })
        list.addView(actionRow("Ends", { Sleep.formatTime(prefs.sleepEnd) }, pro = true) { refresh ->
            pickTime(prefs.sleepEnd) { prefs.sleepEnd = it; refresh() }
        })
        list.addView(actionRow("Nights", { Sleep.describeDays(prefs.sleepDays) }, pro = true) { refresh ->
            val checked = BooleanArray(7) { (prefs.sleepDays and (1 shl it)) != 0 }
            AlertDialog.Builder(this)
                .setTitle("Which nights?")
                .setMultiChoiceItems(Sleep.dayNames.toTypedArray(), checked) { _, i, v -> checked[i] = v }
                .setPositiveButton("Save") { _, _ ->
                    prefs.sleepDays = checked.indices.filter { checked[it] }.sumOf { 1 shl it }
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        list.addView(actionRow("Apps blocked", {
            val n = prefs.sleepApps.size
            val all = TrackedApps.names.values.toSet().size
            if (n >= all) "All social apps" else "$n of $all apps"
        }, pro = true) { refresh ->
            val names = TrackedApps.names.values.distinct()
            val checked = BooleanArray(names.size) { names[it] in prefs.sleepApps }
            AlertDialog.Builder(this)
                .setTitle("Block during sleep")
                .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, v -> checked[i] = v }
                .setPositiveButton("Save") { _, _ ->
                    prefs.sleepApps = names.filterIndexed { i, _ -> checked[i] }.toSet()
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        list.addView(switchRow(
            "Block their websites too",
            "Instagram.com, YouTube.com, TikTok.com… in browsers",
            prefs.sleepBlockBrowsers,
            pro = true,
        ) { prefs.sleepBlockBrowsers = it })
        list.addView(switchRow(
            "Allow emergency unlock",
            "Unlock for 5 minutes after waiting 30 seconds",
            prefs.sleepAllowUnlock,
            pro = true,
        ) { prefs.sleepAllowUnlock = it })
        list.addView(switchRow(
            "Strict lock",
            "During sleep, NoScroll can't be turned off, changed or uninstalled",
            prefs.sleepStrict,
            pro = true,
        ) { prefs.sleepStrict = it })
    }

    private fun buildAdBlockSettings() {
        val list = findViewById<LinearLayout>(R.id.adblock_list)
        if (!AdBlockFeature.available) {
            // Not offered in the Google Play version (Play policy).
            listOf(R.id.adblock_title, R.id.adblock_list, R.id.adblock_desc)
                .forEach { findViewById<View>(it).visibility = View.GONE }
            return
        }
        list.addView(switchRow(
            "Block ads & trackers",
            "In all apps and browsers",
            prefs.adBlockEnabled,
            pro = true,
        ) { on ->
            if (on) {
                val consent = AdBlockFeature.consentIntent(this)
                if (consent != null) {
                    // Android asks the user once to allow NoScroll to set up a VPN.
                    startActivityForResult(consent, REQUEST_VPN)
                } else {
                    prefs.adBlockEnabled = true
                    AdBlockFeature.start(this)
                }
            } else {
                prefs.adBlockEnabled = false
                AdBlockFeature.stop(this)
            }
        }.also { adBlockRow = it })
        list.addView(actionRow("Filter adult sites in all apps", {
            if (prefs.adBlockUpstream == "adult") "On (CleanBrowsing adult filter + SafeSearch)" else "Off"
        }) { refresh ->
            prefs.adBlockUpstream = if (prefs.adBlockUpstream == "adult") "plain" else "adult"
            refresh()
            Toast.makeText(this, "Takes effect for new lookups", Toast.LENGTH_SHORT).show()
        })
        list.addView(actionRow("Always-on VPN", { "Keeps the ad blocker running after restarts" }, plainTitle = true) {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                Toast.makeText(this, "Tap ⚙ next to NoScroll → Always-on VPN", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {}
        })
    }

    private var adBlockRow: View? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        val ok = resultCode == RESULT_OK
        prefs.adBlockEnabled = ok
        if (ok) AdBlockFeature.start(this)
        adBlockRow?.findViewById<Switch>(R.id.rule_switch)?.isChecked = ok
    }

    private fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> onPicked(h * 60 + m) }, current / 60, current % 60, false).show()
    }

    // Rows that show a live value and need refreshing in onResume.
    private val refreshers = mutableListOf<() -> Unit>()

    private fun refreshRows() = refreshers.forEach { it() }

    private fun locked(): Boolean {
        if (!Sleep.isLocked(prefs)) return false
        Toast.makeText(this, "Locked until ${Sleep.formatTime(prefs.sleepEnd)} (sleep mode)", Toast.LENGTH_SHORT).show()
        return true
    }

    /** A row with a title and a value underneath; tapping it runs [onClick]. */
    private fun actionRow(
        title: String,
        value: () -> String,
        plainTitle: Boolean = false,
        pro: Boolean = false,
        onClick: (refresh: () -> Unit) -> Unit,
    ): View {
        val row = layoutInflater.inflate(R.layout.row_rule, null, false)
        row.findViewById<Switch>(R.id.rule_switch).visibility = View.GONE
        row.findViewById<TextView>(R.id.rule_title).text = proTitle(title, pro)
        val sub = row.findViewById<TextView>(R.id.rule_subtitle)
        if (!plainTitle) sub.setTextColor(getColor(R.color.accent))
        val refresh = { sub.text = value() }
        refresh()
        refreshers += refresh
        row.setOnClickListener {
            if (pro && needsPro()) return@setOnClickListener
            if (!locked()) onClick(refresh)
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return row
    }

    private fun buildAdultSettings() {
        findViewById<LinearLayout>(R.id.adult_list).addView(switchRow(
            "Block adult websites",
            "Chrome, Samsung Internet, Firefox, Edge, Brave, Opera, DuckDuckGo…",
            prefs.blockAdult,
            pro = true,
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

    /** Adds a star to Pro features for users who don't have Pro. */
    private fun proTitle(title: String, pro: Boolean) =
        if (pro && !Pro.isPurchased(this)) "$title  ⭐" else title

    /** If Pro isn't active, opens the upgrade screen and returns true. */
    private fun needsPro(): Boolean {
        if (prefs.isPro) return false
        Toast.makeText(this, "That's a Pro feature", Toast.LENGTH_SHORT).show()
        openPro()
        return true
    }

    private fun switchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        pro: Boolean = false,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = layoutInflater.inflate(R.layout.row_rule, null, false)
        row.findViewById<TextView>(R.id.rule_title).text = proTitle(title, pro)
        row.findViewById<TextView>(R.id.rule_subtitle).text = subtitle
        val sw = row.findViewById<Switch>(R.id.rule_switch)
        sw.isChecked = checked
        var reverting = false
        sw.setOnCheckedChangeListener { _, v ->
            if (reverting) return@setOnCheckedChangeListener
            // Turning a Pro feature *off* is always allowed.
            if ((pro && v && needsPro()) || locked()) {
                reverting = true
                sw.isChecked = !v
                reverting = false
            } else {
                onChange(v)
            }
        }
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
        label: (Int) -> String = { if (it == 0) "Off" else formatDuration(it * 60_000L) },
        pro: Boolean = false,
    ): View {
        val row = layoutInflater.inflate(R.layout.row_rule, null, false)
        row.findViewById<Switch>(R.id.rule_switch).visibility = View.GONE
        val titleView = row.findViewById<TextView>(R.id.rule_title)
        row.findViewById<TextView>(R.id.rule_subtitle).text = subtitle
        fun refresh() { titleView.text = proTitle("$title:  ${label(get())}", pro) }
        refresh()
        row.setOnClickListener {
            if (pro && needsPro()) return@setOnClickListener
            if (locked()) return@setOnClickListener
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

    companion object {
        private const val REQUEST_VPN = 42
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
