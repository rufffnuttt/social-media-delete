package com.noscroll.app

import android.app.Activity
import android.content.Context

/** Personal build: no store or account, everything is unlocked. */
object Store {
    const val ALWAYS_PRO = true
    const val IS_STORE_BUILD = false
    const val isConfigured = false

    fun isSignedIn(context: Context) = false
    fun signIn(activity: Activity, onDone: (String?) -> Unit) = onDone(null)
    fun signOut(context: Context, onDone: () -> Unit = {}) = onDone()
    fun refresh(context: Context, onDone: () -> Unit = {}) = onDone()
    fun loadPlans(context: Context, onLoaded: (List<Plan>) -> Unit) = onLoaded(emptyList())
    fun buy(activity: Activity, plan: Plan, onDone: () -> Unit = {}) {}
    fun manage(activity: Activity) {}
}
