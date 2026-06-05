# XXPlayer

Minimal Android local video player for old devices.

## What is implemented
- Fixed directory video scanning: `/sdcard/Movies`
- Video select and playback (`MediaPlayer + SurfaceView`)
- Tap video to pause, tap center triangle button to resume
- Per-video persistent play count via local SQLite (`+1` on full completion)
- Read-only bottom progress bar and time display

## Open and run
1. Install Android Studio (standard wizard install).
2. Install SDK components:
   - Android SDK Platform 19
   - Android SDK Platform 34
   - Android SDK Build-Tools
   - Android SDK Platform-Tools
3. Copy `local.properties.example` to `local.properties` and set `sdk.dir`.
4. Open this folder in Android Studio and run `app` on device.

## Build from CLI
```bash
cd /Users/xixiaoyong/code/XXPlayer
GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew :app:assembleDebug
```

## Main files
- `app/src/main/java/com/example/viewerguard/MainActivity.java`
- `app/src/main/java/com/example/viewerguard/VideoStatsDbHelper.java`
- `PROJECT_PLAN.md`
