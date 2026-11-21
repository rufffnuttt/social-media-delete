package com.noscroll.app

import android.content.Context

/**
 * Domains the ad blocker refuses to resolve.
 *
 * The full list (~100k ad / tracker / malware domains from the StevenBlack hosts
 * project) is downloaded into `assets/hosts.txt` when the APK is built. A short
 * built-in list is used if that file is missing.
 */
object Blocklist {
    @Volatile private var ads: Set<String> = emptySet()
    @Volatile private var adult: Set<String> = emptySet()
    @Volatile var loaded = false
        private set

    // Never block these, so common apps keep working even if a list is over-eager.
    private val allow = setOf(
        "localhost", "connectivitycheck.gstatic.com", "clients3.google.com",
        "www.googleapis.com", "android.clients.google.com", "play.googleapis.com",
    )

    private val fallback = listOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "google-analytics.com", "app-measurement.com",
        "admob.com", "ads.google.com", "adnxs.com", "adsrvr.org", "amazon-adsystem.com",
        "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "rubiconproject.com",
        "pubmatic.com", "openx.net", "casalemedia.com", "smartadserver.com", "moatads.com",
        "scorecardresearch.com", "quantserve.com", "adcolony.com", "applovin.com",
        "applvn.com", "unityads.unity3d.com", "vungle.com", "inmobi.com", "mopub.com",
        "chartboost.com", "ironsrc.com", "supersonicads.com", "startappservice.com",
        "appsflyer.com", "adjust.com", "branch.io", "flurry.com", "mixpanel.com",
        "ads.yahoo.com", "advertising.com", "media.net", "zedo.com", "popads.net",
        "propellerads.com", "adsterra.com", "exoclick.com", "trafficjunky.net", "juicyads.com",
        "ads.tiktok.com", "analytics.tiktok.com", "ads-api.tiktok.com", "an.facebook.com",
        "ads.linkedin.com", "ads.pinterest.com", "ads.reddit.com", "ads.twitter.com",
        "static.ads-twitter.com", "adsafeprotected.com", "doubleverify.com", "2mdn.net",
        "serving-sys.com", "yieldmo.com", "sharethrough.com", "teads.tv", "spotxchange.com",
        "hotjar.com", "crashlytics.com", "bugsnag.com",
    )

    fun load(context: Context) {
        if (loaded) return
        val set = HashSet<String>(150_000)
        try {
            context.assets.open("hosts.txt").bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.substringBefore('#').trim()
                    if (line.isEmpty()) continue
                    // Accept "domain" or "0.0.0.0 domain" / "127.0.0.1 domain".
                    val domain = line.split(Regex("\\s+")).last().lowercase()
                    if (domain.contains('.') && domain !in allow) set.add(domain)
                }
            }
        } catch (e: Exception) {
            // No bundled list – fall back to the short one.
        }
        set.addAll(fallback)
        ads = set
        adult = AdultFilter.domainList.toHashSet()
        loaded = true
    }

    val size: Int get() = ads.size

    fun isBlocked(name: String, blockAdult: Boolean): Boolean {
        var d = name.lowercase().trimEnd('.')
        if (d in allow) return false
        while (true) {
            if (d in ads) return true
            if (blockAdult && d in adult) return true
            val dot = d.indexOf('.')
            if (dot < 0 || dot == d.length - 1) return false
            d = d.substring(dot + 1)
            if (!d.contains('.')) return false   // don't match bare TLDs like "com"
        }
    }
}
