# NoScroll

An Android app that removes the endless short-video feeds from social media
**without** blocking the rest of the app.

| App        | Blocked                     | Still works                                   |
|------------|-----------------------------|-----------------------------------------------|
| Instagram  | Reels (tab, and reels opened from DMs/feed) | Home feed, Stories, DMs, profiles, search |
| YouTube    | Shorts (tab and player)     | Regular videos, search, subscriptions         |
| Facebook   | Reels tab (best effort)     | Everything else                               |
| Snapchat   | Spotlight tab               | Chats, camera, Stories                        |
| TikTok     | The whole app (it's only a feed) | –                                        |

Each one can be switched on/off in the app. It also counts how many times it saved you from a feed.

## How it works

NoScroll uses Android's Accessibility feature (the same thing apps like
ScrollGuard use). When it sees a Reels/Shorts player or tab on screen it
presses **Back** for you, so you land back on the normal feed. If Back
doesn't get you out after a couple of tries, it goes to the home screen.

It has **no internet permission** and stores nothing except your switches and
the block counter.

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
4. The status turns green: **Blocking is ON**. Try opening Reels – it should bounce you right back.

Samsung / Xiaomi / some other phones may stop background services to save
battery. If blocking stops after a while, set NoScroll's battery usage to
**Unrestricted** in App info → Battery.

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
