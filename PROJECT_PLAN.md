# XXPlayer - Project Plan

## 1. Goal And Constraints
- Build an Android video player that can run on old devices (Mi Pad 1 compatibility first).
- Keep resource usage low and implementation simple.
- Human presence check must be background and silent.
- If no face is detected, playback must pause.
- If user clicks play again, start a new 30-second detection cycle.

## 2. Confirmed Functional Scope
- Read videos from a fixed local directory (`/sdcard/Movies`).
- Show video list and allow user selection.
- Play selected video with `MediaPlayer + SurfaceView`.
- Detect face every 30 seconds only while playback is active.
- If face detected: do nothing and keep playing.
- If face not detected: pause playback and show resume button.
- Playback count is in-memory only, per video file path.
- Count increments only when one full playback reaches completion.

## 3. Technical Design
- Language: Java.
- Min SDK: 19.
- Core components:
  - Playback: `MediaPlayer`, `SurfaceView`
  - Camera: Camera1 (`android.hardware.Camera`)
  - Face detection: `android.media.FaceDetector`
- Detection workflow:
  - Start/resume playback -> wait 30s -> take one hidden frame -> detect face.
  - Face found -> schedule next 30s detection.
  - Face not found -> pause playback and stop scheduling.
- Silent behavior:
  - No visible camera preview UI.
  - No "detecting" prompt or toast during normal checks.

## 4. Permission And Failure Strategy
- Required runtime permissions:
  - `CAMERA`
  - Video read permission (`READ_EXTERNAL_STORAGE` or `READ_MEDIA_VIDEO` by API level)
- If required permission is denied: show brief message and exit app.
- If detection/camera fails while playing: pause playback for safety.

## 5. Testing And Acceptance
- Video list loads from fixed directory.
- Selecting a video starts playback.
- Face detection triggers after 30s intervals while playing.
- No-face result pauses playback.
- Resume button restarts playback and starts a new 30s cycle.
- Playback count increments only on full completion (`onCompletion`).
- No major memory growth or ANR in 30-minute run.

## 6. Change Log For New Requirements
| Date | Requirement | Decision | Status |
| --- | --- | --- | --- |
| 2026-06-05 | Silent background face check | Use Camera1 hidden frame + FaceDetector | Done |
| 2026-06-05 | Add playback count on page | In-memory per-video count, +1 on completion | Done |
