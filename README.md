# NoScroll

An Android app that removes the endless short-video feeds from social media
**without** blocking the rest of the app.

| App        | Blocked                     | Still works                                   |
|------------|-----------------------------|-----------------------------------------------|
| Instagram  | Reels (tab, and reels opened from DMs/feed) | Home feed, Stories, DMs, profiles, search |
| YouTube    | Shorts (tab and player)     | Regular videos, search, subscriptions         |
| Facebook   | Reels tab (best effort)     | Everything else                               |
| Snapchat   | Spotlight ("Snap reels"): the tab, and Spotlight videos opened from Stories or chats | Chats, camera, friends' Stories |
| TikTok     | The whole app (it's only a feed) | –                                        |

Each one can be switched on/off in the app. It also counts how many times it saved you from a feed.

### Adult website blocking
- Reads the address bar in Chrome, Samsung Internet, Firefox, Edge, Brave, Opera,
  Vivaldi, DuckDuckGo and others, and closes known adult sites and explicit searches.
- One-tap setup for a **phone-wide** filter: Android's *Private DNS* set to
  CleanBrowsing's free adult filter (`adult-filter-dns.cleanbrowsing.org`),
  which also covers other apps and forces Google/Bing SafeSearch.

### Sleep mode
Blocks social media completely during your sleep hours. Everything is configurable:
- start and end time, and which nights (e.g. only school nights)
- which apps are blocked (default: all of them)
- also block their websites in browsers (default on)
- emergency 5-minute unlock after a 30-second wait (default off)
- **strict lock**: during sleep, NoScroll's settings are locked and the Android screens
  for turning it off or uninstalling it are blocked (default off)

### Ad blocker
A local, on-phone VPN that only handles DNS lookups (like Blokada / DNS66). It blocks
~100k ad, tracker and malware domains (StevenBlack hosts list, bundled at build time)
in every app and browser. It can also filter adult sites phone-wide by using
CleanBrowsing's adult-filter DNS, which also forces SafeSearch.
Ads served from the same servers as the content (YouTube and Instagram in-app ads)
can't be removed this way. Turn on **Always-on VPN** for NoScroll in Android's VPN
settings to keep it running after restarts.

### Screen time & reminders
- Counts your time in social apps (Instagram, YouTube, TikTok, Facebook, Snapchat,
  X, Reddit, Threads, Pinterest, Tumblr, Twitch) and shows today's total, a per-app
  breakdown, number of opens and your 7-day average.
- **Daily limit** (default 1h): when you hit it, opening a social app shows a stop
  screen – *Close* or *5 more minutes* (that button unlocks after 10 seconds).
- **Break reminder** (default every 15 min of non-stop scrolling).
- **Take-a-breath pause**: a pause screen (3–30 seconds, configurable) each time you open a social app,
  telling you how many times you've opened it today.

## How it works

NoScroll uses Android's Accessibility feature (the same thing apps like
ScrollGuard use). When it sees a Reels/Shorts player or tab on screen it
presses **Back** for you, so you land back on the normal feed. If Back
doesn't get you out after a couple of tries, it goes to the home screen.

Everything (settings, counters, screen time) stays on your phone. The only network
use is the ad blocker forwarding normal DNS lookups to a DNS server.

## Install on your phone

1. On your Android phone, open
   **https://github.com/rufffnuttt/social-media-delete/releases/latest/download/NoScroll.apk**
   (or go to the repo's **Releases** page and tap `NoScroll.apk`).
2. Open the downloaded file. If asked, allow your browser to *install unknown apps*.
   Play Protect may warn that the app is unknown – tap **Install anyway**.
3. Open **NoScroll** and follow the two steps on screen:
   1. **Android 13+ only:** *Open App info* → ⋮ menu (top right) →
      **Allow restricted settings**. (Android requires this for accessibility apps
      not installed from the Play Store. If you don't see the option, skip it.)
   2. *Open Accessibility settings* → **Installed / Downloaded apps** → **NoScroll** → on → Allow.
4. The status turns green: **NoScroll is ON**. Try opening Reels – it should bounce you right back.

Samsung / Xiaomi / some other phones may stop background services to save
battery. If blocking stops after a while, set NoScroll's battery usage to
**Unrestricted** in App info → Battery.

5. Optional: turn on **Block ads & trackers** and allow the VPN request. It also
   filters adult sites in all apps. If you'd rather not use the ad blocker, use
   **Copy address & open settings** under *Adult content* and paste it into
   **Private DNS → Private DNS provider hostname** instead (don't use both).

## If something slips through

Apps rename their internal screen IDs occasionally. The detection rules live
in [`Rules.kt`](app/src/main/java/com/noscroll/app/Rules.kt) – each app has a
list of player view IDs plus the name of its nav tab. Add the new ID there and
push; GitHub Actions rebuilds the APK automatically.

## Building it yourself

Every push builds `NoScroll.apk` via GitHub Actions
([`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)) and
publishes it to the `latest` release. Locally, with the Android SDK installed:

```sh
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

## iPhone?

iOS does not let apps watch or control other apps, so this approach is
Android-only.
