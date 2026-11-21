package com.noscroll.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Full-screen interruption shown over other apps:
 *  - PAUSE: a short breather before a social app opens
 *  - BREAK: "you've been scrolling for X minutes"
 *  - LIMIT: daily limit reached
 *  - ADULT: an adult website was blocked
 */
class InterruptActivity : Activity() {

    private var timer: CountDownTimer? = null
    private lateinit var mode: String
    private var pkg: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_interrupt)
        val prefs = Prefs(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_BREAK
        pkg = intent.getStringExtra(EXTRA_PKG)
        val appName = pkg?.let { TrackedApps.name(it) } ?: "social media"

        val emoji = findViewById<TextView>(R.id.emoji)
        val title = findViewById<TextView>(R.id.title)
        val message = findViewById<TextView>(R.id.message)
        val primary = findViewById<Button>(R.id.primary)
        val secondary = findViewById<Button>(R.id.secondary)
        val today = formatDuration(prefs.totalUsageMs())

        when (mode) {
            MODE_PAUSE -> {
                emoji.text = "🌿"
                title.text = "Take a breath"
                val opens = pkg?.let { prefs.opens(it) } ?: 0
                message.text = "You're opening $appName" +
                    (if (opens > 1) " for the ${ordinal(opens)} time today." else ".") +
                    "\n\nIs this what you want to be doing right now?\n\nSocial media today: $today"
                primary.text = "Never mind"
                primary.setOnClickListener { leave() }
                secondary.setOnClickListener { pkg?.let { grantPass(it) }; finish() }
                delayButton(secondary, "Open $appName", 5)
            }
            MODE_BREAK -> {
                val sessionMs = intent.getLongExtra(EXTRA_SESSION_MS, 0L)
                emoji.text = "⏰"
                title.text = "Time for a break?"
                message.text = "You've been on social media for ${formatDuration(sessionMs)} straight." +
                    "\n\nStand up, stretch, drink some water, look at something far away." +
                    "\n\nToday so far: $today"
                primary.text = "Take a break"
                primary.setOnClickListener { leave() }
                secondary.text = "Keep going"
                secondary.setOnClickListener {
                    BlockerService.instance?.onKeepGoing()
                    finish()
                }
            }
            MODE_LIMIT -> {
                emoji.text = "🛑"
                title.text = "Daily limit reached"
                message.text = "You've spent $today on social media today " +
                    "(your limit is ${formatDuration(prefs.dailyLimitMin * 60_000L)})." +
                    "\n\nThat's enough for today — the rest of your phone still works."
                primary.text = "Close $appName"
                primary.setOnClickListener { leave() }
                secondary.setOnClickListener {
                    prefs.limitSnoozeUntil = System.currentTimeMillis() + 5 * 60_000L
                    BlockerService.instance?.onKeepGoing()
                    finish()
                }
                delayButton(secondary, "5 more minutes", 10)
            }
            MODE_ADULT -> {
                emoji.text = "🚫"
                title.text = "Site blocked"
                message.text = "NoScroll blocked a page that looked like adult content." +
                    "\n\nIf this was a mistake, you can turn off this filter in NoScroll."
                primary.text = "OK"
                primary.setOnClickListener { leave() }
                secondary.visibility = View.GONE
            }
        }
    }

    /** Disables a button for a few seconds so the choice is a deliberate one. */
    private fun delayButton(button: Button, label: String, seconds: Int) {
        button.isEnabled = false
        button.alpha = 0.5f
        timer = object : CountDownTimer(seconds * 1000L, 250L) {
            override fun onTick(msLeft: Long) {
                button.text = "$label (${(msLeft + 999) / 1000})"
            }
            override fun onFinish() {
                button.text = label
                button.isEnabled = true
                button.alpha = 1f
            }
        }.start()
    }

    /** Goes to the home screen instead of back into the app. */
    private fun leave() {
        val service = BlockerService.instance
        if (service != null) {
            service.goHome()
        } else {
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back shouldn't be a way to skip the pause or the limit.
        leave()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    private fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
        }
        return "$n$suffix"
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_SESSION_MS = "session_ms"
        const val MODE_PAUSE = "pause"
        const val MODE_BREAK = "break"
        const val MODE_LIMIT = "limit"
        const val MODE_ADULT = "adult"

        private val passes = HashMap<String, Long>()
        private const val PASS_MS = 60_000L

        fun grantPass(pkg: String) {
            passes[pkg] = SystemClock.elapsedRealtime() + PASS_MS
        }

        fun hasPass(pkg: String) = (passes[pkg] ?: 0L) > SystemClock.elapsedRealtime()
    }
}
