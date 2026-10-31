# Advanced Tunnel: ready-to-build Android project

I can't produce a finished APK here because there are no Android build tools in this environment. What I can do is give you a complete, corrected project in a ZIP. Open it in Android Studio and click Build > Build APK to get the APK.

## What you get
A file named `AdvancedTunnel-android.zip` in your Files, containing:
- Gradle setup: `settings.gradle.kts`, the root `build.gradle.kts`, `gradle.properties`, and the Gradle wrapper settings
- `app/build.gradle.kts`, with the broken line in the constraintlayout dependency fixed
- `AndroidManifest.xml`, with the namespace URL corrected and a foreground-service permission plus service type added
- `activity_main.xml`, a new screen layout. Your paste didn't include one. It has fields for server IP, port, SNI host and payload, plus a Start/Stop button
- `TunnelData.kt`, `AdvancedVpnService.kt` and `MainActivity.kt`, with these fixes:
  - Missing closing braces added
  - Words that were split across lines (`getSt ring`, `vpnInter face`, `start Service`) joined back together
  - `super.onCreate(savedInstanceState: Bundle?)` changed to `super.onCreate(savedInstanceState)`
  - `private synchronized fun` changed to `@Synchronized private fun`, since Kotlin has no `synchronized` keyword
  - The service now runs as a foreground service with a notification. Without this, Android 8 and later stops it
  - The VPN excludes its own app (`addDisallowedApplication`) so the tunnel's own connection doesn't loop back into itself
- Live speed panel from your new notes. It shows download and upload speed every second and a status line (Disconnected / Connecting / Connected). Upload speed is counted too, not left at 0. Updates stay inside the app, which also prevents a crash on Android 14.
- Share codes from your new notes (`ConfigEncoder.kt`). Export copies a `TUNNEL://...` code to the clipboard, and Import pastes a code to fill in the fields. Note: this code is only Base64, so anyone can read it. It is not encrypted.
- The service sets the button and status correctly when the tunnel stops or fails
- `README.md` with step-by-step instructions to build the APK in Android Studio

## Limitation
Your code sends raw device packets straight into the TLS socket. That only works if your VPS runs a matching server that accepts raw IP packets after the payload. I'll say this in the README and won't change how the tunnel works.

## Technical details
I'll write the files under /tmp, list and check them, zip them, and copy the ZIP to /mnt/documents. Nothing in the web app changes.
