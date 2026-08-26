# Fukuro 🦉

A personal Android client for [Audiobookshelf](https://www.audiobookshelf.org/), built for sideloading.

## Features

- **Streaming + background playback** — Media3/ExoPlayer foreground service with notification & lock-screen controls (−10 s / +30 s instead of prev/next)
- **Android Auto** — browse *Continue Listening*, *Downloaded* and *Library* from the car screen
- **Offline downloads** — books stored on-device; playable with no server connection, including in Android Auto
- **Progress sync** — position syncs to the server every 15 s and on pause; resumes anywhere; cached locally when offline
- **Series & authors** — real series grouping from the server, author portraits, tap through to a grid of their books
- **Chapters** — collapsible chapter list in the player, current chapter always visible, tap to seek
- **Favorites** — heart any book (stored on-device) and get a Favorites shelf
- **Mark finished / reset progress / rename** — per book, synced to the server
- **Upload books** — add an Audiobookshelf API key in Settings, then upload audio files straight from the phone
- **Sleep timer & playback speed** — bottom sheets with presets plus custom values (speed up to 10×)
- **Customizable home screen** — toggle & reorder: Continue Listening, Favorites, Downloaded, Series, Authors, All Books
- **Theming** — light / dark / system, eight accent colors (default: Fukuro orange) or Material You
- **Server connection indicator** — green/red dot in the top bar
- **Listening statistics** — period summaries and charts, listening habits, completed-book
  highlights, five meaningful recent sessions, and a shareable year-in-review card
- **Local listening history** — Fukuro records elapsed playback time on-device and combines it
  with Audiobookshelf history, including offline and on-device books

## Project layout

Everything lives in one flat `fukuro` package — no deep `com/example/...` nesting, no sub-packages:

```
build.gradle.kts          plugin versions
settings.gradle.kts       repositories
app/build.gradle.kts      android config + all dependencies
app/src/main/
  AndroidManifest.xml
  res/                    icon, colors, strings, Android Auto descriptor
  java/fukuro/
    ShelfApp.kt           app singleton (store, api, downloads)
    Api.kt                Audiobookshelf REST client
    Models.kt             API data classes
    Store.kt              settings + favorites (DataStore)
    Downloads.kt          offline downloads
    PlayerService.kt      Media3 playback + Android Auto browse tree
    MainActivity.kt       navigation, bottom bar, mini player
    ShelfViewModel.kt     UI state
    Screens.kt            login, home, library, series, author, book
    PlayerScreen.kt       full player
    StatsScreen.kt        listening charts, habits, recent sessions, year in review
    SettingsScreen.kt     settings
    UploadScreen.kt       upload a book
    Theme.kt              color schemes / dark mode
    CoverImage.kt         cover & author images with placeholders
```

## Building

Requirements: JDK 17, Android SDK (platform 35, build-tools 35).

```
gradle assembleDebug
```

APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Prebuilt APKs are attached to each
[release](../../releases) — download one on the phone and open it to install.

Debug builds use the `nl.codefin.fukuro.glassdev` application ID and the name **Fukuro Test**,
so they update the test installation without replacing the published app.

## First run

1. Open the app and enter your Audiobookshelf server URL (e.g. `http://192.168.x.x:13378`).
2. Log in with your Audiobookshelf username/password.
3. Cleartext HTTP is enabled for LAN use; for access outside your home network put the server behind
   HTTPS (reverse proxy) or use a VPN.

## Notes

- Debug-signed: fine for personal sideloading; new builds update **Fukuro Test** and keep its data.
- Android Auto with sideloaded apps: in the Android Auto app, enable *Developer settings → Unknown sources*.
- Favorites are stored on the device (Audiobookshelf has no favorites concept), so they don't appear in the web UI.
