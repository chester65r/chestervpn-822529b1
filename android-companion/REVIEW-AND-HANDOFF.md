# ChesterVPN Companion — independent development handoff

**Purpose:** continue the native Android companion module in another development app or AI without relying on previous chat context.

## Repository and branch

- Repository: https://github.com/chester65r/chestervpn-822529b1
- Working branch: `safe-app-shell`
- Original/default branch: `main`
- Native module: `android-companion/`
- Build workflow: `.github/workflows/companion-apk.yml`
- Do not merge this branch into `main` without a separate review and explicit approval.
- Keep all future changes for this app on `safe-app-shell` unless the owner explicitly requests otherwise.

## Product scope

The current deliverable is a **native Android companion utility**, not a working VPN client.

Implemented:
- Jetpack Compose user interface: Home, Activity, Settings, About.
- Read-only Android connectivity summary via `ACCESS_NETWORK_STATE`.
- Local device diagnostics: Android release, API level, app version.
- Local event history in app-private SharedPreferences, capped at 50 entries.
- Activity filtering and clear-history action.
- Persistent light/dark theme preference.
- User-initiated support report generation, preview, copy and Android share sheet.
- Unit tests for diagnostics/report contents, empty history and 50-event limit.
- A Python manifest guard plus four regression tests for the approved permission set, VPN-service absence, cleartext setting, and Android backup setting.
- GitHub Actions workflow for manifest scope verification, tests, lint, debug APK assembly, SHA-256 checksum and artifact upload.

Privacy / permissions:
- Manifest requests only `android.permission.ACCESS_NETWORK_STATE`.
- No Internet, VPN, location, phone, contacts or storage permissions.
- The app does not create tunnels, proxy traffic, intercept packets, change routes, inspect destinations, identify carriers, inspect SIM details or determine data-plan entitlements.
- It does not collect credentials, phone numbers, advertising IDs or network traffic.
- Diagnostics and support reports are generated locally only after user actions. Review reports before sharing.
- Android backup is disabled for this module.

Do not add features intended to evade carrier billing, package limits, free-data eligibility or network access controls. Do not add carrier-specific payloads, SNI rules, hostnames or equivalent workarounds. Preserve unrelated existing repository files.

## Android toolchain

| Setting | Value |
|---|---|
| Application ID | `com.chester65r.chestervpncompanion` |
| Minimum SDK | 26 (Android 8.0) |
| Compile SDK | 36 |
| Target SDK | 36 (Android 16) |
| JDK | 17 |
| Android Gradle Plugin | 8.13.2 |
| Gradle in CI | 8.13 |
| Kotlin Gradle plugin | 2.3.21 |
| Compose BOM | 2026.06.01 |
| Activity Compose | 1.13.0 |
| AndroidX Core | 1.17.0 |

Target API 36 is deliberate: starting August 31, 2026, Google Play requires new apps and updates for mobile apps to target Android 16 / API 36 or higher. Android 16 edge-to-edge is enabled in `MainActivity`; verify layout insets on Android 15 and 16 devices.

The Kotlin Gradle plugin 2.3.21 compatibility range includes AGP 8.13.2 and Gradle 8.13. AGP 8.13 requires Gradle 8.13. See:
- https://kotlinlang.org/docs/gradle-configure-project.html
- https://developer.android.com/build/releases/about-agp
- https://developer.android.com/google/play/requirements/target-sdk
- https://developer.android.com/develop/ui/compose/bom

## Source map

- `app/src/main/AndroidManifest.xml` — permissions and launcher activity.
- `app/src/main/java/.../MainActivity.kt` — Compose UI, navigation, theme-aware system-bar icons and actions.
- `DeviceDiagnostics.kt` — version-aware PackageInfo lookup and local diagnostic formatting.
- `NetworkReadiness.kt` — read-only Android connectivity summary.
- `LocalAppStore.kt` — private theme preference and dated, bounded event log.
- `SupportReport.kt` — support report formatter with 50-event cap.
- `app/src/test/.../DeviceDiagnosticsTest.kt` — diagnostics privacy-field test.
- `app/src/test/.../SupportReportTest.kt` — report fields, empty event history and 50-event cap.
- `app/build.gradle.kts` — Android/Kotlin/Compose dependencies.
- `scripts/verify_scope.py` — static manifest guard for this local-only companion.
- `scripts/test_verify_scope.py` — positive and negative regression tests for the manifest guard.
- `.github/workflows/companion-apk.yml` — CI workflow.

## CI investigation and fixes already made

Actual historical GitHub Actions logs showed:
1. An earlier AGP 9.5.0 configuration failed because AGP 9's built-in Kotlin support rejects the separate `org.jetbrains.kotlin.android` plugin. The module was moved to AGP 8.13.2, Gradle 8.13 and Kotlin 2.3.21.
2. `android-actions/setup-android@v3` invoked the retired `sdkmanager tools` package, which failed with `Failed to find package 'tools'`. This action was removed; CI now uses the hosted runner's preinstalled SDK at `/usr/local/lib/android/sdk`.
3. The SDK license command printed that licenses were accepted but returned exit code 1 due to a `yes | sdkmanager` pipe under `pipefail`. It was changed to Bash process substitution so the SDK manager's exit code is preserved.
4. A diagnostics test incorrectly asserted that the text `SIM` must not appear even though the privacy notice intentionally mentions that SIM data is not collected. The assertion now checks explicit sensitive-field labels instead.
5. CI now targets API 36 / Build Tools 36.0.0, runs unit tests and lint before APK assembly, generates a SHA-256 checksum, uploads APK and checksum as a 14-day artifact, and cancels superseded runs.
6. Workflow path filters skip README-only changes.
7. Added `scripts/verify_scope.py` and wired it into CI before the Android build. It requires `ACCESS_NETWORK_STATE` as the only declared permission, keeps cleartext and Android backup disabled, and rejects a declared system VPN service.
8. Added four standard-library regression tests covering an accepted local-only manifest and rejection of Internet permission, a system VPN service, cleartext, and backup. The guard and all four tests passed in a local isolated test run; the updated GitHub Actions run still needs to be checked.

**Last verified successful Android source build (November 3, 2026):** GitHub Actions run [#41602747276](https://github.com/chester65r/chestervpn-822529b1/actions/runs/41602747276) built source commit `7726d76b195da6ee81749a6f62e6f559d761eb58`. Its `build` job completed successfully, including SDK setup, license acceptance, API 36 installation, unit tests, lint, debug APK assembly, SHA-256 generation, and artifact upload. The uploaded artifact is `chester-vpn-companion-debug` (artifact ID `13214973128`, 11,107,920 bytes), available until November 17, 2026. SHA-256: `3571801f1d30dac465e29946785c5de54d477b517588606f8d350e63c3daea6a`.

After that successful build, a static manifest scope guard and its workflow step were added in commit `92324fa2375f3b24ae0e12f55ace7b82d62050f6`; README and handoff documentation were also updated. The earlier run verifies the app source but does not verify the newly added scope-guard step. The new CI run must pass before treating the updated workflow as verified; this handoff was updated again after adding the regression tests, so check the newest run rather than relying on the older successful artifact. No physical-device installation has been performed. The artifact is a debug APK and is not release-signed.

Latest workflow page:
https://github.com/chester65r/chestervpn-822529b1/actions/workflows/companion-apk.yml

## Build command

From the repository root, with JDK 17, Gradle 8.13 and Android SDK 36 installed:

```bash
gradle --no-daemon -p android-companion testDebugUnitTest lintDebug assembleDebug
```

Expected debug APK:
```
android-companion/app/build/outputs/apk/debug/app-debug.apk
```

The workflow also creates `app-debug.apk.sha256` and uploads both files. It now runs `python3 android-companion/scripts/verify_scope.py` plus `python3 -m unittest discover -s android-companion/scripts -p "test_*.py"` before Gradle. The debug APK is for testing only; it is not a release-signed APK.

**Known tooling gap:** Gradle Wrapper scripts/JAR are not checked in yet. Add `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` and `gradle-wrapper.properties` pinned to Gradle 8.13, then switch CI to `./gradlew`.

## Recommended next steps (priority order)

1. Verify the newest CI run that includes `verify_scope.py` and its four regression tests; the earlier November 3, 2026 run passed Android tests, lint, APK assembly and artifact upload before the scope guard was added. Inspect and fix actual logs if the updated workflow fails.
2. Add the Gradle Wrapper and update CI to use it.
3. Test installs and UI on Android 15 and Android 16: edge-to-edge insets, bottom navigation, scroll, font scaling, RTL, clipboard, share sheet, lifecycle and process recreation.
4. Add Compose UI/instrumentation tests for navigation, theme persistence, event filtering/clearing, report preview/copy/share and permission expectations.
5. Replace the letter-only bottom-navigation icons with accessible vector icons; validate TalkBack labels, content descriptions, target sizes, contrast and RTL layout.
6. Add adaptive launcher icons (including monochrome icon) and a splash screen.
7. Keep SharedPreferences while the app is small; consider Preferences DataStore for preferences and bounded Room storage only if history becomes more complex.
8. After install/device testing, prepare a separate release build/AAB with private signing via GitHub Actions secrets, checksums and provenance. Never commit keystores, passwords, API keys or private certificates.
9. Re-review data minimization and privacy wording any time report fields or diagnostics change.

## Definition of done

- Latest Actions run passes the local-only scope guard, its four regression tests, Android unit tests, lint and `assembleDebug`.
- APK and SHA-256 artifact are downloadable from that successful run.
- APK installs and launches on Android 15 and 16.
- All four screens, theme switching, diagnostics, network refresh, event filter/clear, report preview/copy/share are tested.
- Manifest permissions remain minimal.
- The original `main` branch has no remaining companion source files or handoff document. An accidentally misplaced handoff file was briefly added to `main` and then deleted; the add/delete pair remains in commit history, so the branch graph is ahead/behind rather than a clean ancestor relationship. No pre-existing application source file was changed.
- No feature is described as real VPN functionality unless it has actually been implemented, authorized and verified.

## Instructions to the next developer / AI

First inspect the latest `safe-app-shell` branch head, this handoff, and the latest GitHub Actions run. Do not assume a passing build. Work only on the native companion scope described here. Do not merge into `main` without explicit approval. Do not invent successful test results, releases, deployed services or APK artifacts.
