#!/bin/sh
# Runs inside reactivecircus/android-emulator-runner after the virtual phone boots.
# POSIX sh on purpose: the action invokes its script with /bin/sh, not Bash.

api_level="${1:-unknown}"
evidence="$GITHUB_WORKSPACE/emulator-artifacts/api-$api_level"
mkdir -p "$evidence" || exit 1

adb_bounded() {
  # An emulator can go offline after a failed instrumentation run. Never let
  # one diagnostic command hold the matrix job indefinitely.
  timeout 15s adb "$@"
}

collect_evidence() {
  adb_bounded logcat -d -v threadtime > "$evidence/logcat.txt" 2>&1 || true
  adb_bounded shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb_bounded pull /sdcard/window.xml "$evidence/window.xml" >/dev/null 2>&1 || true
  adb_bounded exec-out screencap -p > "$evidence/final-screen.png" 2>/dev/null || true
  adb_bounded pull /sdcard/device-smoke.png \
    "$evidence/device-smoke.png" >/dev/null 2>&1 || true
  adb_bounded pull /sdcard/device-smoke-collapsed.png \
    "$evidence/device-smoke-collapsed.png" >/dev/null 2>&1 || true
  adb_bounded pull /sdcard/device-smoke-expanded.png \
    "$evidence/device-smoke-expanded.png" >/dev/null 2>&1 || true
  adb_bounded pull /sdcard/settings-before-toggle.png \
    "$evidence/settings-before-toggle.png" >/dev/null 2>&1 || true
  adb_bounded pull /sdcard/settings-after-toggle.png \
    "$evidence/settings-after-toggle.png" >/dev/null 2>&1 || true
  adb_bounded shell dumpsys activity processes > "$evidence/activity-processes.txt" 2>&1 || true
  adb_bounded shell dumpsys jobscheduler > "$evidence/jobscheduler.txt" 2>&1 || true
  adb_bounded shell dumpsys notification > "$evidence/notifications.txt" 2>&1 || true
}

status=0
(
  cd "$GITHUB_WORKSPACE/app/android" || exit 1
  ./gradlew :app:connectedDebugAndroidTest --console=plain
) || status=$?

collect_evidence
exit "$status"
