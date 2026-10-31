#!/usr/bin/env bash
# Build + credential instrumentation evidence; never dump logcat or enrollment/config contents.
set -euo pipefail
project="${1:?Usage: run-checks.sh <generated-project> <build|emulator|physical> [serial]}"
mode="${2:-build}"
serial="${3:-}"
[[ "$mode" == build || "$mode" == emulator || "$mode" == physical ]] || { echo "Invalid mode"; exit 1; }
[[ -f "$project/gradlew" && -f "$project/app/build.gradle.kts" ]] || { echo "Generate project and install pinned wrapper first; see README."; exit 1; }
[[ -n "${JAVA_HOME:-}" && -n "${ANDROID_HOME:-}" ]] || { echo "Set JDK 17 JAVA_HOME and Android SDK ANDROID_HOME"; exit 1; }
java_version="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
[[ "$java_version" =~ \"17[\.\"] ]] || { echo "JDK 17 required"; exit 1; }
project="$(realpath "$project")"
evidence="$project/test-evidence/$(date -u +%Y%m%dT%H%M%SZ)-$mode"
mkdir -p "$evidence"
printf 'UTC: %s\nMode: %s\nJDK: %s\nNative VPN network tests: NOT RUN\n' "$(date -u +%FT%TZ)" "$mode" "$java_version" > "$evidence/result.txt"
"$project/gradlew" -p "$project" --no-daemon assembleDebug assembleRelease assembleDebugAndroidTest lintDebug > "$evidence/build.log" 2>&1 || { echo "Build failed; see local build.log"; exit 1; }
printf 'Build: PASS (release is unsigned, NOT distribution approved)\n' >> "$evidence/result.txt"
if [[ "$mode" != build ]]; then
  [[ -n "$serial" ]] || { echo "Specify a device serial explicitly; never choose a device automatically"; exit 1; }
  export ANDROID_SERIAL="$serial"
  adb="$ANDROID_HOME/platform-tools/adb"
  [[ "$("$adb" -s "$serial" get-state)" == device ]] || { echo "Device unavailable"; exit 1; }
  qemu="$("$adb" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')"
  if [[ "$mode" == emulator && "$qemu" != 1 ]] || [[ "$mode" == physical && "$qemu" == 1 ]]; then
    echo "Device kind does not match requested evidence class"; exit 1
  fi
  api="$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
  abi="$("$adb" -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
  [[ "$api" =~ ^[0-9]+$ && "$api" -ge 26 ]] || { echo "API 26+ required"; exit 1; }
  printf 'API: %s\nABI: %s\n' "$api" "$abi" >> "$evidence/result.txt"
  # Address exactly one device; connected* Gradle tasks may select several attached devices.
  "$adb" -s "$serial" install -r "$project/app/build/outputs/apk/debug/app-debug.apk" > "$evidence/install.log" 2>&1
  "$adb" -s "$serial" install -r "$project/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >> "$evidence/install.log" 2>&1
  "$adb" -s "$serial" shell am instrument -w -r -e class com.example.advancedtunnel.NativeCredentialsTest com.example.advancedtunnel.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/instrumentation.log" 2>&1
  # am instrument can return zero even on test failure: require the actual test result.
  grep -Eq '^OK \([1-9][0-9]* tests?\)' "$evidence/instrumentation.log" && ! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$evidence/instrumentation.log" || { echo "Instrumentation failed or no passing test record"; exit 1; }
  printf 'Native credential instrumentation: PASS\nHardware-backed key attestation: NOT RUN\nVPN consent/network/production server: NOT RUN\n' >> "$evidence/result.txt"
fi
echo "Evidence saved to $evidence; record manual device tests separately."