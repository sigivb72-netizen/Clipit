# Clip It

Copy a link, get the video. Clip It is an Android app that picks up links you
copy and downloads them with [gallery-dl](https://github.com/mikf/gallery-dl)
and [yt-dlp](https://github.com/yt-dlp/yt-dlp), both running on the phone.

## What it does

- **Copy to download.** With Clip It switched on, a copied link from a known
  media site starts downloading. Links can also be shared to Clip It, pasted
  in the app, or grabbed from the notification.
- **Quick settings tile.** A toggle next to torch and aeroplane mode turns
  watching on and off. Off means no background service at all.
- **Logins.** The Accounts tab signs in to X/Twitter, Instagram, TikTok,
  Reddit, Facebook, YouTube and others in a web view and passes the session to
  the downloaders as a `cookies.txt`. Any other site can be added by address,
  a `cookies.txt` can be imported, and username/password logins are supported
  for sites gallery-dl signs in to directly. A site shows **Ready** once its
  session cookies are present.
- **Quality.** One setting, 720p by default, applied to yt-dlp and gallery-dl.
- **Progress** in the app and in notifications, with cancel and retry.
- **History.** Tap to open. Press and hold for redownload, details (original
  link, uploader, and everything else the post reported), open original post,
  share, copy link and delete.
- Files are saved to `Download/Clip It`.
- Also: Wi-Fi only, simultaneous download limit, a cap on files per link (so a
  profile link does not fetch thousands of posts), resume after reboot, and an
  in-app updater for both download engines.

## How copy detection works

Android 10 and later only let the app on screen read the clipboard. Clip It
therefore offers two modes:

1. **Instant grab** (Settings → Turn on). An accessibility service notices the
   moment something is copied and reads the link. On Android 13+ a sideloaded
   app must first be allowed "restricted settings" from its App info page.
2. **Without it**, copy the link and tap *Grab copied link* in the Clip It
   notification or quick settings, or use the share sheet.

## Routing

A link goes to gallery-dl when it has an extractor for the site, and to yt-dlp
otherwise (YouTube and most pure video sites). If gallery-dl finds nothing,
yt-dlp gets a try.

## Building

Needs JDK 17 and the Android SDK (platform 35).

    ./gradlew assembleRelease

APKs land in `app/build/outputs/apk/release/`, one per CPU type; almost every
phone wants `arm64-v8a`. The release build is signed with the local debug key
so it installs directly. APKs from different machines (including CI) carry
different keys, so uninstall before switching between them, or configure your
own keystore in `app/build.gradle.kts`.

Python, yt-dlp, QuickJS and ffmpeg come from
[youtubedl-android](https://github.com/yausername/youtubedl-android);
gallery-dl and its dependencies are fetched from PyPI at build time with
pinned hashes. The driver that ties them together is
`app/src/main/assets/engine/clipit_engine.py`.

## Licence

Clip It bundles gallery-dl (GPL-2.0) and ffmpeg via youtubedl-android
(GPL-3.0), so distributed builds fall under the GPL.
