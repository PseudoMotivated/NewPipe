# NewPipe Podcast (fork)

NewPipe plus a home-screen bar widget that turns your last playlist into a podcast:

- The player remembers the last queue with 2+ items (any playlist, channel, or local playlist).
- The widget shows the current episode with prev / play-pause / next.
  Tapping a button starts background audio. The app does not open.
- Make the widget taller to see the episodes around the current one. Tap one to play it.
- NewPipe's "resume playback" setting continues each episode where you stopped.

## Install and keep your data

App ID `org.schabi.newpipe.podcast`. It sits next to stock NewPipe. Stock NewPipe is signed
with the NewPipe team's key, so this fork can't update it in place. Move your data once:

1. Stock NewPipe → Settings → Backup & Restore → Export database.
2. Install `NewPipe-Podcast-*.apk`, then Settings → Backup & Restore → Import database.

Every later fork APK signed with the same key installs over the previous one and keeps your data.

## Build

```sh
export JAVA_HOME=~/Android/jdk21 ANDROID_HOME=~/Android/sdk
./gradlew assembleRelease   # app/build/outputs/apk/release/
```

Signing reads `podcastStoreFile`, `podcastStorePassword`, `podcastKeyAlias` and
`podcastKeyPassword` from `~/.gradle/gradle.properties`. **Back up the keystore.** If you lose it,
future APKs can't update the installed app. Bump `PODCAST_FORK_REVISION` in
`buildSrc/src/main/kotlin/ProjectConfig.kt` for each release.
