# Camera

Samsung-style camera app for the M-KOPA X30 (Kotlin, Jetpack Compose, CameraX). Built by GitHub Actions on every push to `main`; each build is published as a GitHub release `v1.0.N` with `Camera-N.apk`.

- `camera/` — `CameraEngine` (CameraX binding per mode, zoom/ultra-wide, Pro controls, video, shared analysis frames), `Shooter` (what the shutter does per mode), `Caps` (Camera Check probe), `RawShooter` (DNG via Camera2), `Saver` (DCIM/Camera)
- `process/` — filters and My Filters, portrait, night stacking, alignment, panorama, hyperlapse encoder, slow-motion re-timing, Pro meters
- `ml/` — ML Kit (scene optimizer, QR, text, translate, identify, faces), MediaPipe palm gesture, voice commands
- `ui/` — camera screen, mode panels, gallery, settings, Camera Check

The palm-gesture model (`gesture_recognizer.task`, Apache-2.0, Google MediaPipe) is downloaded during the CI build.
