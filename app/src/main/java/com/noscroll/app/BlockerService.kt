package com.noscroll.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Watches the supported apps and backs out of short-video feeds as soon as one appears.
 */
class BlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private var checkPending = false
    private var lastActionAt = 0L
    private var strikes = 0
    private var lastToastAt = 0L

    private val checkRunnable = Runnable {
        checkPending = false
        checkScreen()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val rule = Rules.forPackage(pkg) ?: return
        if (!prefs.isEnabled(rule)) return

        if (rule.blockWholeApp && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            block(rule, goHome = true)
            return
        }
        // Coalesce the flood of content-changed events into one check every ~100ms.
        if (!checkPending) {
            checkPending = true
            handler.postDelayed(checkRunnable, CHECK_DELAY_MS)
        }
    }

    private fun checkScreen() {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return
        val rule = Rules.forPackage(root.packageName?.toString()) ?: return
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

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val CHECK_DELAY_MS = 100L
        private const val COOLDOWN_MS = 700L
        private const val STRIKE_RESET_MS = 2500L
        private const val TOAST_GAP_MS = 4000L
    }
}
