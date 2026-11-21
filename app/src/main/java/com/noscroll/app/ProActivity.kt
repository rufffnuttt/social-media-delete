package com.noscroll.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * The upgrade screen: what Pro includes, trial status, Google sign-in and the plans.
 * Payment happens with Stripe on the DB Audio Services website; Pro is tied to the
 * Google account, so it works on every phone where the user signs in.
 * Also opened by the website after payment via the noscroll://pro link.
 */
class ProActivity : Activity() {

    private var pendingPlan: Plan? = null

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

        findViewById<Button>(R.id.btn_signin).setOnClickListener { signIn() }
        findViewById<Button>(R.id.btn_signout).setOnClickListener {
            Store.signOut(this) { refreshStatus() }
        }
        findViewById<Button>(R.id.btn_restore).setOnClickListener {
            if (!Store.isSignedIn(this)) {
                signIn()
                return@setOnClickListener
            }
            Toast.makeText(this, "Checking your account…", Toast.LENGTH_SHORT).show()
            Store.refresh(this) {
                refreshStatus()
                Toast.makeText(
                    this,
                    if (Pro.isPurchased(this)) "Pro restored ⭐" else "No Pro purchase found for ${Prefs(this).accountEmail}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        findViewById<Button>(R.id.btn_manage).setOnClickListener { Store.manage(this) }
        findViewById<Button>(R.id.btn_close).setOnClickListener { finish() }
        loadPlans()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // Coming back from Stripe Checkout in the browser: pick up the new status.
        Store.refresh(this) {
            val wasPro = findViewById<View>(R.id.plans_section).visibility == View.GONE
            refreshStatus()
            if (!wasPro && Pro.isPurchased(this)) {
                Toast.makeText(this, "Welcome to NoScroll Pro ⭐", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun signIn() {
        Store.signIn(this) { error ->
            refreshStatus()
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                pendingPlan = null
            } else {
                Toast.makeText(this, "Signed in as ${Prefs(this).accountEmail}", Toast.LENGTH_SHORT).show()
                // If they tapped a plan before signing in, continue to checkout.
                pendingPlan?.let { plan -> pendingPlan = null; if (!Pro.isPurchased(this)) Store.buy(this, plan) }
            }
        }
    }

    private fun refreshStatus() {
        val prefs = Prefs(this)
        val purchased = Pro.isPurchased(this)
        val signedIn = Store.isSignedIn(this)

        findViewById<TextView>(R.id.pro_status).text = when {
            purchased -> Pro.statusLine(this) + "\nThank you for supporting NoScroll!"
            Pro.trialActive(this) -> {
                val d = Pro.trialDaysLeft(this)
                "Your free trial has $d day${if (d == 1) "" else "s"} left. Upgrade to keep these features afterwards."
            }
            else -> "Your ${Pro.TRIAL_DAYS}-day free trial has ended. Pro features are switched off until you upgrade."
        }

        val account = findViewById<TextView>(R.id.account_line)
        account.text = if (signedIn) "Signed in as ${prefs.accountEmail}"
        else "Sign in with Google to buy Pro or restore it on this phone."
        val storeBuild = Store.IS_STORE_BUILD
        findViewById<View>(R.id.account_section).visibility = if (storeBuild) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_signin).visibility = if (storeBuild && !signedIn) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_signout).visibility = if (storeBuild && signedIn) View.VISIBLE else View.GONE

        findViewById<View>(R.id.plans_section).visibility = if (purchased) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_manage).visibility =
            if (signedIn && prefs.proKind == "subscription") View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_restore).visibility = if (storeBuild) View.VISIBLE else View.GONE
    }

    private fun loadPlans() {
        val list = findViewById<LinearLayout>(R.id.plan_list)
        val loading = findViewById<TextView>(R.id.plans_loading)
        if (!Store.IS_STORE_BUILD) {
            loading.text = "This is the personal build, so everything is already unlocked."
            return
        }
        if (!Store.isConfigured) {
            loading.text = "Purchasing isn't set up in this build yet."
            return
        }
        Store.loadPlans(this) { plans ->
            list.removeAllViews()
            if (plans.isEmpty()) {
                loading.text = "Couldn't load plans. Check your connection and try again."
                return@loadPlans
            }
            loading.visibility = View.GONE
            for (plan in plans) {
                val btn = layoutInflater.inflate(R.layout.row_plan, list, false)
                btn.findViewById<TextView>(R.id.plan_title).text = plan.title +
                    if (plan.id == "yearly") "  ·  best value" else ""
                btn.findViewById<TextView>(R.id.plan_price).text = plan.price
                btn.setOnClickListener {
                    if (Store.isSignedIn(this)) {
                        Store.buy(this, plan)
                    } else {
                        pendingPlan = plan
                        signIn()
                    }
                }
                list.addView(btn)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
