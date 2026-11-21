package com.noscroll.app

import android.content.Context
import android.content.Intent

/**
 * Google Play build: no ad blocker. Play policy forbids apps that block or
 * interfere with ads shown by other apps.
 */
object AdBlockFeature {
    const val available = false
    val running: Boolean get() = false
    fun consentIntent(context: Context): Intent? = null
    fun start(context: Context) {}
    fun stop(context: Context) {}
}
