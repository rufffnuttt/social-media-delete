package com.noscroll.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * The heart of NoScroll. Runs as an accessibility service and:
 *  - backs out of Reels / Shorts / Spotlight feeds and closes TikTok,
 *  - closes adult websites in supported browsers,
 *  - tracks time in social apps and shows break reminders, a daily limit,
 *    and an optional "take a breath" pause when a social app is opened.
 */
class BlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    // Feed / content checks
    private var checkPending = false
    private var lastActionAt = 0L
    private var strikes = 0
    private var lastToastAt = 0L
    private var lastAdultBlockAt = 0L

    // Screen time
    private var fgPkg: String? = null
    private var fgSince = 0L
    private var sessionStart = 0L
    private var lastSocialAt = 0L
    private var lastBreakReminderAt = 0L
    private var lastInterruptAt = 0L
    private val lastSeen = HashMap<String, Long>()

    private val checkRunnable = Runnable {
        checkPending = false
        checkScreen()
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> setForeground(null)
                Intent.ACTION_USER_PRESENT -> tick()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        instance = this
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::prefs.isInitialized) return
        val pkg = event?.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !isIgnorable(pkg)) {
            setForeground(pkg)
        }

        val rule = Rules.forPackage(pkg)
        val enabledRule = rule != null && prefs.isEnabled(rule)
        if (enabledRule && rule!!.blockWholeApp &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            block(rule, goHome = true)
            return
        }

        val watchBrowser = prefs.blockAdult && Browsers.isBrowser(pkg)
        if ((enabledRule || watchBrowser) && !checkPending) {
            // Coalesce the flood of content-changed events into one check every ~100ms.
            checkPending = true
            handler.postDelayed(checkRunnable, CHECK_DELAY_MS)
        }
    }

    // ---------------------------------------------------------------------------
    // Feed + adult content checks
    // ---------------------------------------------------------------------------

    private fun checkScreen() {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return
        val pkg = root.packageName?.toString()

        if (prefs.blockAdult && Browsers.isBrowser(pkg)) {
            val url = try { Browsers.readUrl(root) } catch (e: Exception) { null }
            if (url != null && AdultFilter.isAdult(url)) blockAdult()
            return
        }

        val rule = Rules.forPackage(pkg) ?: return
        if (!prefs.isEnabled(rule)) return
        val matched = try {
            rule.matches(root)
        } catch (e: Exception) {
            false
        }
        if (matched) {
            block(rule, goHome = rule.blockWholeApp)
        } else if (SystemClock.elapsedRealtime() - lastActionAt > STRIKE_RESET_MS) {
            strikes = 0
        }
    }

    private fun block(rule: AppRule, goHome: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < COOLDOWN_MS) return

        // If pressing Back keeps landing us on the feed (e.g. the app was opened
        // straight into Reels), fall back to the home screen.
        strikes = if (now - lastActionAt < STRIKE_RESET_MS) strikes + 1 else 1
        lastActionAt = now
        val home = goHome || strikes >= 3

        performGlobalAction(if (home) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
        if (home) strikes = 0
        prefs.recordBlock()

        if (now - lastToastAt > TOAST_GAP_MS) {
            lastToastAt = now
            val msg = if (rule.blockWholeApp) "NoScroll closed ${rule.title.substringBefore(" (")}"
            else "NoScroll blocked ${rule.title}"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Re-check shortly after, in case the feed is still on screen.
        handler.removeCallbacks(checkRunnable)
        checkPending = true
        handler.postDelayed(checkRunnable, COOLDOWN_MS + 50)
    }

    private fun blockAdult() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdultBlockAt < ADULT_COOLDOWN_MS) return
        lastAdultBlockAt = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        prefs.recordBlock()
        showInterrupt(InterruptActivity.MODE_ADULT, null, force = true)
    }

    // ---------------------------------------------------------------------------
    // Screen time + reminders
    // ---------------------------------------------------------------------------

    private fun isIgnorable(pkg: String): Boolean {
        if (pkg == "com.android.systemui" || pkg == "android") return true
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return ime != null && ime.startsWith("$pkg/")
    }

    /** Records which app is in front, saving time spent in the previous one. */
    private fun setForeground(pkg: String?) {
        if (pkg == fgPkg) return
        commitUsage()
        val prev = fgPkg
        fgPkg = pkg
        fgSince = SystemClock.elapsedRealtime()
        if (pkg != null && TrackedApps.isTracked(pkg)) onEnterSocialApp(pkg, prev)
    }

    private fun commitUsage() {
        val pkg = fgPkg ?: return
        val now = SystemClock.elapsedRealtime()
        if (TrackedApps.isTracked(pkg)) {
            prefs.addUsage(pkg, now - fgSince)
            lastSocialAt = now
            lastSeen[pkg] = now
        }
        fgSince = now
    }

    private fun onEnterSocialApp(pkg: String, prev: String?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSocialAt > SESSION_GAP_MS) {
            sessionStart = now
            lastBreakReminderAt = now
        }
        val rule = Rules.forPackage(pkg)
        if (rule != null && rule.blockWholeApp && prefs.isEnabled(rule)) return

        val freshOpen = now - (lastSeen[pkg] ?: 0L) > REOPEN_GAP_MS && prev != pkg
        if (freshOpen) prefs.recordOpen(pkg)

        when {
            limitReached() -> showInterrupt(InterruptActivity.MODE_LIMIT, pkg, force = true)
            freshOpen && prefs.mindfulPause && !InterruptActivity.hasPass(pkg) ->
                showInterrupt(InterruptActivity.MODE_PAUSE, pkg)
        }
    }

    private fun limitReached(): Boolean {
        val limit = prefs.dailyLimitMin
        if (limit <= 0) return false
        if (System.currentTimeMillis() < prefs.limitSnoozeUntil) return false
        return prefs.totalUsageMs() >= limit * 60_000L
    }

    private fun tick() {
        if (!::prefs.isInitialized) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive) {
            setForeground(null)
            return
        }
        // Catch app switches that didn't produce an event (e.g. after unlocking).
        val activePkg = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
        if (activePkg != null && !isIgnorable(activePkg)) setForeground(activePkg)

        val pkg = fgPkg ?: return
        if (!TrackedApps.isTracked(pkg)) return
        commitUsage()

        val now = SystemClock.elapsedRealtime()
        val breakMs = prefs.breakReminderMin * 60_000L
        when {
            limitReached() -> showInterrupt(InterruptActivity.MODE_LIMIT, pkg)
            breakMs > 0 && now - sessionStart >= breakMs && now - lastBreakReminderAt >= breakMs -> {
                lastBreakReminderAt = now
                showInterrupt(InterruptActivity.MODE_BREAK, pkg, sessionMs = now - sessionStart)
            }
        }
    }

    private fun showInterrupt(mode: String, pkg: String?, sessionMs: Long = 0L, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastInterruptAt < INTERRUPT_GAP_MS) return
        lastInterruptAt = now
        val intent = Intent(this, InterruptActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(InterruptActivity.EXTRA_MODE, mode)
            .putExtra(InterruptActivity.EXTRA_PKG, pkg)
            .putExtra(InterruptActivity.EXTRA_SESSION_MS, sessionMs)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "NoScroll: time for a break", Toast.LENGTH_LONG).show()
        }
    }

    /** Called by [InterruptActivity] when the user chooses to keep going. */
    fun onKeepGoing() {
        val now = SystemClock.elapsedRealtime()
        lastBreakReminderAt = now
        lastInterruptAt = now
    }

    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        commitUsage()
        instance = null
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        var instance: BlockerService? = null
            private set

        private const val CHECK_DELAY_MS = 100L
        private const val COOLDOWN_MS = 700L
        private const val STRIKE_RESET_MS = 2500L
        private const val TOAST_GAP_MS = 4000L
        private const val ADULT_COOLDOWN_MS = 1500L
        private const val TICK_MS = 15_000L
        private const val SESSION_GAP_MS = 3 * 60_000L
        private const val REOPEN_GAP_MS = 60_000L
        private const val INTERRUPT_GAP_MS = 8_000L
    }
}
