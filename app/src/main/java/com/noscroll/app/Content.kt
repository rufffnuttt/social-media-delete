package com.noscroll.app

import android.view.accessibility.AccessibilityNodeInfo

/** Browsers NoScroll can read the address bar of, and the view IDs of their URL fields. */
object Browsers {
    private val urlBarIds: Map<String, List<String>> = mapOf(
        "com.android.chrome" to listOf("url_bar"),
        "com.chrome.beta" to listOf("url_bar"),
        "com.chrome.dev" to listOf("url_bar"),
        "com.chrome.canary" to listOf("url_bar"),
        "com.brave.browser" to listOf("url_bar"),
        "com.microsoft.emmx" to listOf("url_bar"),
        "com.vivaldi.browser" to listOf("url_bar"),
        "com.kiwibrowser.browser" to listOf("url_bar"),
        "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text", "custom_tab_toolbar_url_bar_text"),
        "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title", "mozac_browser_toolbar_edit_url_view"),
        "org.mozilla.firefox_beta" to listOf("mozac_browser_toolbar_url_view", "mozac_browser_toolbar_edit_url_view"),
        "org.mozilla.focus" to listOf("display_url", "mozac_browser_toolbar_url_view"),
        "com.opera.browser" to listOf("url_field"),
        "com.opera.mini.native" to listOf("url_field"),
        "com.duckduckgo.mobile.android" to listOf("omnibarTextInput"),
        "com.mi.globalbrowser" to listOf("url", "search_hint"),
        "com.UCMobile.intl" to listOf("titlebar_text"),
    )

    fun isBrowser(pkg: String?) = pkg != null && urlBarIds.containsKey(pkg)

    /** Returns whatever text is in the browser's address bar, or null if it can't be found. */
    fun readUrl(root: AccessibilityNodeInfo): String? {
        val pkg = root.packageName?.toString() ?: return null
        val ids = urlBarIds[pkg] ?: return null
        for (id in ids) {
            for (node in root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")) {
                val text = node.text?.toString()
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }
}

/**
 * Recognises adult websites and searches from address-bar text.
 *
 * Two checks: a list of well-known adult domains, and keywords that are very
 * unlikely to appear in ordinary URLs or searches.
 */
object AdultFilter {
    private val domains = listOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com", "youporn.com",
        "tube8.com", "spankbang.com", "eporner.com", "tnaflix.com", "motherless.com", "erome.com",
        "hqporner.com", "porntrex.com", "thumbzilla.com", "beeg.com", "txxx.com", "hclips.com",
        "porn.com", "youjizz.com", "drtuber.com", "sunporno.com", "fapello.com", "rule34.xxx",
        "rule34.paheal.net", "e621.net", "nhentai.net", "hanime.tv", "hentaihaven.xxx",
        "onlyfans.com", "fansly.com", "chaturbate.com", "stripchat.com", "bongacams.com",
        "livejasmin.com", "camsoda.com", "cam4.com", "myfreecams.com", "flirt4free.com",
        "brazzers.com", "realitykings.com", "bangbros.com", "naughtyamerica.com", "mofos.com",
        "xart.com", "playboy.com", "manyvids.com", "clips4sale.com", "iwantclips.com",
        "literotica.com", "f95zone.to", "nutaku.net", "adultfriendfinder.com", "ashleymadison.com",
        "sxyprn.com", "porngeek.com", "theporndude.com", "missav.com", "javhd.com", "jable.tv",
        "noodlemagazine.com", "pornpics.com", "imagefap.com", "sex.com", "4tube.com", "porntube.com",
        "pornone.com", "slutload.com", "ixxx.com", "xxxbunker.com", "vporn.com", "fuq.com",
        "redgifs.com", "scrolller.com", "coomer.su", "kemono.su", "simpcity.su", "thothub.to",
    )

    private val keywords = listOf(
        "porn", "xxx", "hentai", "xvideo", "xnxx", "xhamster", "redtube", "youporn", "onlyfans",
        "chaturbate", "stripchat", "nsfw", "rule34", "nhentai", "sexcam", "sextube", "freesex",
        "camgirl", "milf", "nude", "boobs", "pussy", "blowjob", "cumshot",
        "gangbang", "creampie", "deepthroat", "bdsm", "erotic", "camsex", "sexting",
        "lesbian sex", "anal sex", "sex video", "sex videos", "sex tape", "leaked nudes",
    )

    fun isAdult(rawUrl: String): Boolean {
        val text = rawUrl.lowercase().trim()
        val host = text
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .substringBefore('/').substringBefore('?').substringBefore(' ')
        if (domains.any { host == it || host.endsWith(".$it") }) return true

        // Turn "google.com/search?q=sex+video" and "sex-video" into "sex video".
        val words = text.replace("%20", " ").replace(Regex("[+_\\-./?=&#:]+"), " ")
        return keywords.any { kw ->
            if (kw.contains(' ')) words.contains(kw)
            else text.contains(kw) && !isFalsePositive(words, kw)
        }
    }

    // Avoid blocking innocent words that happen to contain a keyword.
    private val allowed = listOf("pornography addiction", "porn addiction", "quit porn", "milford", "milfoil", "pussy cat", "pussycat", "pussy willow")
    private fun isFalsePositive(words: String, kw: String) =
        allowed.any { it.contains(kw) && words.contains(it) }
}

/** Apps whose time counts as "social media" for screen time and reminders. */
object TrackedApps {
    val names: Map<String, String> = linkedMapOf(
        "com.instagram.android" to "Instagram",
        "com.google.android.youtube" to "YouTube",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
        "com.facebook.katana" to "Facebook",
        "com.snapchat.android" to "Snapchat",
        "com.twitter.android" to "X",
        "com.reddit.frontpage" to "Reddit",
        "com.instagram.barcelona" to "Threads",
        "com.pinterest" to "Pinterest",
        "com.tumblr" to "Tumblr",
        "tv.twitch.android.app" to "Twitch",
    )

    fun isTracked(pkg: String?) = pkg != null && names.containsKey(pkg)
    fun name(pkg: String) = names[pkg] ?: pkg
}
