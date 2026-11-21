package com.noscroll.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** The upgrade screen: what Pro includes, trial status, and the plans. */
class ProActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pro)

        val features = findViewById<LinearLayout>(R.id.pro_features)
        for ((emoji, text) in Pro.features) {
            features.addView(TextView(this).apply {
                this.text = "$emoji   $text"
                textSize = 16f
                setTextColor(getColor(R.color.text))
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        findViewById<TextView>(R.id.pro_free_note).text =
            "Always free: Reels, Shorts & TikTok blocking, screen time stats and break reminders."

        findViewById<Button>(R.id.btn_restore).setOnClickListener {
            Toast.makeText(this, "Checking your purchases…", Toast.LENGTH_SHORT).show()
            Store.refresh(this) {
                refreshStatus()
                Toast.makeText(
                    this,
                    if (Pro.isPurchased(this)) "Pro restored ⭐" else "No purchases found for this Google account",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        findViewById<Button>(R.id.btn_manage).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://play.google.com/store/account/subscriptions?sku=${PLAY_SUB_ID}&package=$packageName"
                ))
            )
        }
        findViewById<Button>(R.id.btn_close).setOnClickListener { finish() }
        loadPlans()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val purchased = Pro.isPurchased(this)
        val status = findViewById<TextView>(R.id.pro_status)
        status.text = when {
            purchased -> Pro.statusLine(this) + "\nThank you for supporting NoScroll!"
            Pro.trialActive(this) -> {
                val d = Pro.trialDaysLeft(this)
                "Your free trial has $d day${if (d == 1) "" else "s"} left. Upgrade to keep these features afterwards."
            }
            else -> "Your ${Pro.TRIAL_DAYS}-day free trial has ended. Pro features are switched off until you upgrade."
        }
        findViewById<View>(R.id.plans_section).visibility = if (purchased) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_manage).visibility =
            if (Prefs(this).proKind == "subscription") View.VISIBLE else View.GONE
    }

    private fun loadPlans() {
        val list = findViewById<LinearLayout>(R.id.plan_list)
        val loading = findViewById<TextView>(R.id.plans_loading)
        if (!Store.IS_STORE_BUILD) {
            loading.text = "This is the personal build, so everything is already unlocked."
            return
        }
        Store.loadPlans(this) { plans ->
            list.removeAllViews()
            if (plans.isEmpty()) {
                loading.text = "Couldn't load prices from Google Play. Check your connection and try again."
                return@loadPlans
            }
            loading.visibility = View.GONE
            for (plan in plans) {
                val btn = layoutInflater.inflate(R.layout.row_plan, list, false)
                btn.findViewById<TextView>(R.id.plan_title).text = plan.title +
                    if (plan.id == "yearly") "  ·  best value" else ""
                btn.findViewById<TextView>(R.id.plan_price).text = plan.price
                btn.setOnClickListener {
                    Store.buy(this, plan) {
                        refreshStatus()
                        if (Pro.isPurchased(this)) {
                            Toast.makeText(this, "Welcome to NoScroll Pro ⭐", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                list.addView(btn)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val PLAY_SUB_ID = "noscroll_pro"
    }
}
