# ChesterVPN Companion — independent development handoff

**Handoff purpose:** continue the native Android companion app in another development app/AI without relying on previous chat context.

## Repository and branch

- Repository: https://github.com/chester65r/chestervpn-822529b1
- Working branch: `safe-app-shell`
- Original branch: `main`
- All companion files are under `android-companion/`.
- Do not merge this branch into `main` without a separate review and explicit approval.
- Do not rewrite, remove, or alter existing files outside the newly added companion module and its dedicated build workflow.

## Product definition / scope

The current deliverable is a native Android companion utility. It is **not currently a VPN client**.

Implemented behavior:
- Home dashboard
- Android connectivity summary from the read-only `ACCESS_NETWORK_STATE` permission
- Local device diagnostics (Android release, API level, app version)
- Local activity log stored in app-private SharedPreferences (50-event maximum)
- Activity filter and clear history
- Persistent light/dark theme setting
- Explicitly user-generated support report, preview, copy, and Android share sheet
- About and privacy information

This module requests no Internet, VPN, location, phone, contacts, or storage permissions. Its network panel does not inspect carrier identity, SIM data, destinations, traffic, data-plan status, or tunnel state. It does not create a tunnel, proxy, traffic route, or packet interception system. Keep this limitation explicit in all future handoffs and UI claims.

Do not add features designed to evade carrier billing, free-data eligibility, subscription/package limits, or network access controls. Do not introduce carrier-specific hostnames, payloads, SNI rules, or equivalent workarounds. Existing unrelated material elsewhere in the repository has not been removed or modified; leave it untouched.

## Current Android toolchain

| Setting | Value |
|---|---|
| Minimum SDK | 26 |
| Compile SDK | 36 |
| Target SDK | 36 |
| JDK | 17 |
| Android Gradle Plugin | 8.13.2 |
| Gradle distribution in CI | 8.13 |
| Kotlin Gradle plugin | 2.3.21 |
| Compose BOM | 2026.09.00 |
| Activity Compose | 1.13.0 |
| Application ID | `com.chester65r.chestervpncompanion` |

Target API 36 is intentional: starting August 31, 2026, new mobile apps and app updates submitted to Google Play must target Android 16 / API 36 or higher. Edge-to-edge is enabled in `MainActivity`; Android 16 layout must be tested on a physical/emulated device.

The selected Kotlin Gradle plugin supports AGP 8.13.2 and Gradle 8.13 according to the official Kotlin compatibility matrix. AGP 8.13 requires Gradle 8.13.

## Key source files

- `app/src/main/AndroidManifest.xml` — the module's permission and launcher declaration.
- `app/src/main/java/.../MainActivity.kt` — Compose UI, navigation and user interactions.
- `DeviceDiagnostics.kt` — local Android/app metadata only.
- `NetworkReadiness.kt` — read-only Android connectivity status.
- `LocalAppStore.kt` — private local theme preference and bounded event history.
- `SupportReport.kt` — user-requested report formatter.
- `app/src/test/.../DeviceDiagnosticsTest.kt` — diagnostics report test.
- `app/src/test/.../SupportReportTest.kt` — report field/empty-log tests.
- `app/build.gradle.kts` — app toolchain and dependencies.
- `.github/workflows/companion-apk.yml` — CI build and APK artifact workflow.

## CI history and known build problem

Earlier GitHub Actions failures were investigated from their actual job logs:

1. The first build ran with an earlier Android Gradle Plugin 9.5.0 configuration and failed while applying `org.jetbrains.kotlin.android`. The AGP 9 line reported that this plugin is no longer required by its built-in Kotlin support. This was corrected by pinning AGP 8.13.2 and using Gradle 8.13 with Kotlin 2.3.21.
2. Later runs failed inside `android-actions/setup-android@v3`: the action invoked `sdkmanager tools`, and the SDK repository returned `Failed to find package 'tools'`. That action has been removed from the workflow.
3. The current workflow uses the hosted runner's preinstalled SDK at `/usr/local/lib/android/sdk`, accepts licenses, installs `platforms;android-36` and `build-tools;36.0.0`, then runs Gradle tests, lint, and debug APK assembly.
4. The workflow was updated to cancel superseded runs and avoid starting a build for README-only edits.

**Do not assume the build is green.** The updated workflow must finish after this handoff revision. Verify the newest Actions run before claiming an APK exists. The build can still uncover Kotlin, Compose, lint, SDK image, or dependency-resolution issues. Fix those from actual logs, one at a time.

Latest workflow page: https://github.com/chester65r/chestervpn-822529b1/actions/workflows/companion-apk.yml

## Exact CI build commands

From the repository root, with Gradle 8.13, JDK 17 and Android SDK 36 installed:

```bash
gradle --no-daemon -p android-companion testDebugUnitTest lintDebug assembleDebug
```

Expected debug APK path:

```
android-companion/app/build/outputs/apk/debug/app-debug.apk
```

The Actions workflow also creates `app-debug.apk.sha256` and uploads both files in a 14-day artifact.

## Critical missing items / recommendations

Prioritize these in order:

1. **Get a green CI build.** Do not add new features before the Android build, tests, and lint all pass.
2. **Add the Gradle Wrapper.** Check in `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle-wrapper.properties` pinned to Gradle 8.13. Update CI to use `./gradlew` rather than relying on a separately installed Gradle binary.
3. **Test Android 15 and 16 UI.** Verify status/navigation bar insets, the bottom navigation, scroll behavior, clipboard and share sheet on real devices or emulators. Test at smaller screen sizes and large font scale.
4. **Add UI/instrumentation tests.** Cover screen navigation, theme persistence, report preview/copy, event filtering/clearing, and no-network-permission assertions.
5. **Add app identity assets.** Adaptive launcher icon, monochrome icon, splash screen, and consistent product naming. Current shell uses the system default icon.
6. **Improve accessibility.** Replace letter-only bottom navigation glyphs with proper vector icons, verify content descriptions, touch targets, contrast, TalkBack labels, and RTL layout.
7. **Move local persistence behind a repository.** SharedPreferences is acceptable for the small current settings/log, but consider Preferences DataStore and a bounded Room event table if history/features grow. Do not collect unnecessary device data.
8. **Prepare an actual release pipeline only after testing.** Add a versioned release build, uploadable AAB, signing through GitHub Actions secrets, checksum, artifact provenance, and a private signing-key rotation/backup plan. Never commit keystores, passwords, API keys, or private certificates.
9. **Review privacy wording whenever fields change.** Every new support-report field must be explicitly reviewed for data minimization and user approval before sharing.

## Definition of done for the next developer

- Latest Actions run passes unit tests, lint, and assembleDebug.
- APK artifact can be downloaded from that successful run.
- SHA-256 checksum is published alongside the APK.
- APK installs and launches on Android 15 and Android 16.
- No runtime crash while switching all four screens or using diagnostics, refresh, copy, share, settings, and clear history.
- Manifest permissions are reviewed and remain minimal.
- `main` and all pre-existing unrelated files are unchanged.
- No feature is described as real VPN functionality unless it is implemented, authorized, and actually verified.

## Instructions for the next AI/developer

Treat this document as project context, not as permission to expand the application scope. Work only on `safe-app-shell` unless the owner explicitly requests another branch. First inspect the current branch and current Actions logs; then fix the build and provide evidence. Do not invent successful builds, test results, deployed services, or APK artifacts.
