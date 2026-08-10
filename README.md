# Shelfplayer

A personal Android client for [Audiobookshelf](https://www.audiobookshelf.org/), built for sideloading.

## Features

- **Streaming + background playback** — Media3/ExoPlayer foreground service with notification & lock-screen controls
- **Android Auto** — browse *Continue Listening*, *Downloaded*, and *Library* from the car screen
- **Offline downloads** — books stored on-device (`Download for offline` on any book); playable with no server connection, including in Android Auto
- **Progress sync** — playback position syncs to the Audiobookshelf server every 15 s and on pause; resumes anywhere; offline progress cached locally
- **Mark finished / reset progress** — per book, from the book page
- **Server connection indicator** — green/red dot in the top bar (30 s ping)
- **Customizable home screen** — toggle & reorder sections: Continue Listening, Downloaded, Series, Authors, All Books
- **Sleep timer** — 10–90 min presets with a gentle volume fade
- **Dark / light / system theme** — with Material You dynamic color on Android 12+

## Building

Requirements: JDK 17, Android SDK (platform 35, build-tools 35).

```
gradle assembleDebug
```

APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Sideload it (enable *Install unknown apps* for your file manager / browser on the phone).

## First run

1. Open the app; server URL is pre-filled (`http://192.168.2.48:13378` — change to your own Audiobookshelf server).
2. Log in with your Audiobookshelf username/password.
3. Cleartext HTTP is enabled for LAN use; for access outside your home network put the server behind HTTPS (reverse proxy) or use a VPN.

## Notes

- Debug-signed: fine for personal sideloading; uninstall/reinstall keeps data unless you clear it.
- Android Auto with sideloaded apps: enable *Developer settings → Unknown sources* inside the Android Auto app on the phone.
