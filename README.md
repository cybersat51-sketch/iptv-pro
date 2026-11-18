# IPTV PRO2 v1.6 — Source Android

Fixed server: http://lgpro.top (HTTP deliberately retained at owner request).

Changes: bounded background network workers and queue; stale request response rejection; retry UI replacement instead of stacking; bounded video format fallback; guarded delayed playback retry; resume position applied once per playback; latest-only search queue.

Known limitations: HTTP exposes credentials in transit. Full JSON catalog still must be loaded into memory before filtering. Android APK build, device playback and server API behavior are NOT verified. Requires Android Studio with Android Gradle Plugin 8.7.3 and Android SDK 35.
