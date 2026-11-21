package com.noscroll.app

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * How NoScroll recognises a short-video feed inside each app.
 *
 * Apps change their internal view IDs from time to time, so each rule uses
 * two independent signals:
 *  1. [viewIds]   – IDs of the full-screen Reels/Shorts player.
 *  2. [tabLabels] – the bottom/top navigation tab for the feed, when it is the selected tab.
 *  3. [fullScreenIdHints] – any view whose ID contains one of these words *and* fills most
 *     of the screen (so a small nav button with the same word doesn't count).
 *
 * If a rule matches, the service presses Back to leave the feed, which keeps the
 * rest of the app (home feed, DMs, search, profiles, normal videos) usable.
 * Apps that are nothing *but* an endless video feed (TikTok) use [blockWholeApp].
 */
data class AppRule(
    val key: String,
    val title: String,
    val subtitle: String,
    val packages: List<String>,
    val viewIds: List<String> = emptyList(),
    val tabLabels: List<String> = emptyList(),
    val fullScreenIdHints: List<String> = emptyList(),
    val blockWholeApp: Boolean = false,
) {
    fun matches(root: AccessibilityNodeInfo): Boolean {
        if (blockWholeApp) return true
        val pkg = root.packageName?.toString() ?: return false
        for (id in viewIds) {
            val fullId = if (id.contains(":id/")) id else "$pkg:id/$id"
            val found = root.findAccessibilityNodeInfosByViewId(fullId)
            if (found.any { it.isVisibleToUser }) return true
        }
        for (label in tabLabels) {
            if (isSelectedTab(root, label)) return true
        }
        if (fullScreenIdHints.isNotEmpty() && hasFullScreenView(root)) return true
        return false
    }

    /** Walks the screen looking for a large view whose ID mentions the feed. */
    private fun hasFullScreenView(root: AccessibilityNodeInfo): Boolean {
        val screen = Rect()
        root.getBoundsInScreen(screen)
        val minHeight = screen.height() * 0.6
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val bounds = Rect()
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val id = node.viewIdResourceName?.lowercase()
            if (id != null && fullScreenIdHints.any { id.contains(it) } && node.isVisibleToUser) {
                node.getBoundsInScreen(bounds)
                if (bounds.height() >= minHeight) return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun isSelectedTab(root: AccessibilityNodeInfo, label: String): Boolean {
        // Searches both visible text and content descriptions (what screen readers say).
        val nodes = root.findAccessibilityNodeInfosByText(label)
        for (node in nodes) {
            if (!node.isVisibleToUser) continue
            val name = (node.contentDescription ?: node.text)?.toString()?.trim() ?: continue
            // "Shorts", "Reels", "Reels, tab 3 of 5" – but not "Watch more Reels from ..."
            if (!name.startsWith(label, ignoreCase = true)) continue
            if (name.length > label.length + 20) continue
            if (node.isSelected || node.parent?.isSelected == true) return true
        }
        return false
    }
}

private const val MAX_NODES = 600

object Rules {
    val all = listOf(
        AppRule(
            key = "instagram",
            title = "Instagram Reels",
            subtitle = "Feed, Stories, DMs and posts still work",
            packages = listOf("com.instagram.android"),
            viewIds = listOf(
                "clips_viewer_view_pager",
                "clips_viewer_container",
                "clips_viewer_fragment_container",
            ),
            // Instagram marks the Reels nav button as selected when you're on it.
            tabLabels = listOf("Reels"),
            // Note: "reel_viewer" is Instagram's *Stories* viewer, so it's deliberately not here.
            fullScreenIdHints = listOf("clips_viewer"),
        ),
        AppRule(
            key = "youtube",
            title = "YouTube Shorts",
            subtitle = "Normal videos, search and subscriptions still work",
            packages = listOf("com.google.android.youtube"),
            viewIds = listOf(
                "reel_recycler",
                "reel_player_page_container",
                "reel_watch_player",
                "reel_watch_fragment_root",
            ),
            tabLabels = listOf("Shorts"),
            fullScreenIdHints = listOf("reel_watch", "reel_player", "shorts_player"),
        ),
        AppRule(
            key = "facebook",
            title = "Facebook Reels",
            subtitle = "Best effort – Facebook hides most of its layout",
            packages = listOf("com.facebook.katana"),
            tabLabels = listOf("Reels"),
        ),
        AppRule(
            key = "snapchat",
            title = "Snapchat Spotlight (Snap reels)",
            subtitle = "Chats, camera and friends' Stories still work",
            packages = listOf("com.snapchat.android"),
            tabLabels = listOf("Spotlight"),
            // Catches Spotlight videos opened from the Stories/Discover page or a chat, too.
            fullScreenIdHints = listOf("spotlight"),
        ),
        AppRule(
            key = "tiktok",
            title = "TikTok (whole app)",
            subtitle = "TikTok is only a scrolling feed, so it's closed entirely",
            packages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            blockWholeApp = true,
        ),
    )

    private val byPackage: Map<String, AppRule> =
        all.flatMap { rule -> rule.packages.map { it to rule } }.toMap()

    fun forPackage(pkg: String?): AppRule? = pkg?.let { byPackage[it] }
}
