package com.noscroll.app

import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Personal build: the DNS ad blocker is available. */
object AdBlockFeature {
    const val available = true

    val running: Boolean get() = AdBlockVpnService.running

    /** Android's one-time VPN consent screen, or null if already granted. */
    fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

    fun start(context: Context) = AdBlockVpnService.start(context)

    fun stop(context: Context) = AdBlockVpnService.stop(context)
}
