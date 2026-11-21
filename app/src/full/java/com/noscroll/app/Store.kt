package com.noscroll.app

import android.app.Activity
import android.content.Context

/** Personal build: no store, everything is unlocked. */
object Store {
    const val ALWAYS_PRO = true
    const val IS_STORE_BUILD = false

    fun refresh(context: Context, onDone: () -> Unit = {}) = onDone()

    fun loadPlans(context: Context, onLoaded: (List<Plan>) -> Unit) = onLoaded(emptyList())

    fun buy(activity: Activity, plan: Plan, onDone: () -> Unit = {}) {}
}
