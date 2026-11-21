package com.noscroll.app

import android.content.Context

/** One purchasable plan shown on the upgrade screen. */
data class Plan(
    val id: String,
    val title: String,
    val price: String,
    /** Store-specific product object (Play's ProductDetails). */
    val details: Any?,
    /** Which subscription base plan to buy; null for one-time products. */
    val offerToken: String?,
)

/**
 * Free vs Pro.
 *
 * Free: Reels / Shorts / TikTok blocking, screen time stats, break reminders.
 * Pro:  adult site blocking, daily limit, take-a-breath pause, sleep mode, ad blocker.
 *
 * Every new install gets a [TRIAL_DAYS]-day trial with all of Pro, no card needed.
 */
object Pro {
    const val TRIAL_DAYS = 7
    private const val DAY_MS = 24L * 60 * 60 * 1000

    val features = listOf(
        "🔞" to "Block adult websites",
        "🌙" to "Sleep mode with strict lock",
        "🛑" to "Daily time limit",
        "🌿" to "Take-a-breath pause before social apps",
    ) + if (AdBlockFeature.available) listOf("🛡️" to "Ad & tracker blocker") else emptyList()

    fun isActive(context: Context): Boolean =
        Store.ALWAYS_PRO || Prefs(context).proKind.isNotEmpty() || trialActive(context)

    fun isPurchased(context: Context): Boolean =
        Store.ALWAYS_PRO || Prefs(context).proKind.isNotEmpty()

    @Volatile private var cachedTrialEnd = 0L

    /** When the trial ends, based on when the app was first installed. */
    fun trialEndsAt(context: Context): Long {
        if (cachedTrialEnd != 0L) return cachedTrialEnd
        val installed = try {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        } catch (e: Exception) {
            Prefs(context).firstSeen
        }
        cachedTrialEnd = installed + TRIAL_DAYS * DAY_MS
        return cachedTrialEnd
    }

    fun trialActive(context: Context) = System.currentTimeMillis() < trialEndsAt(context)

    fun trialDaysLeft(context: Context): Int {
        val ms = trialEndsAt(context) - System.currentTimeMillis()
        return if (ms <= 0) 0 else ((ms + DAY_MS - 1) / DAY_MS).toInt()
    }

    /** One line describing the current plan, for the main screen. */
    fun statusLine(context: Context): String {
        val kind = Prefs(context).proKind
        return when {
            Store.ALWAYS_PRO -> "Personal build · all features unlocked"
            kind == "lifetime" -> "⭐ NoScroll Pro · Lifetime"
            kind == "subscription" -> "⭐ NoScroll Pro · Subscription active"
            trialActive(context) -> {
                val d = trialDaysLeft(context)
                "Free trial · $d day${if (d == 1) "" else "s"} of Pro left"
            }
            else -> "Free version"
        }
    }
}
