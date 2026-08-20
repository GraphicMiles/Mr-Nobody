#!/bin/sh
# Runs inside reactivecircus/android-emulator-runner after the virtual phone boots.
# POSIX sh on purpose: the action invokes its script with /bin/sh, not Bash.

api_level="${1:-unknown}"
evidence="$GITHUB_WORKSPACE/emulator-artifacts/api-$api_level"
mkdir -p "$evidence" || exit 1

collect_evidence() {
  adb logcat -d -v threadtime > "$evidence/logcat.txt" 2>&1 || true
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/window.xml "$evidence/window.xml" >/dev/null 2>&1 || true
  adb exec-out screencap -p > "$evidence/final-screen.png" 2>/dev/null || true
  adb pull /sdcard/Android/data/com.mrnobody.browser/files/device-smoke.png \
    "$evidence/device-smoke.png" >/dev/null 2>&1 || true
  adb shell dumpsys activity processes > "$evidence/activity-processes.txt" 2>&1 || true
  adb shell dumpsys jobscheduler > "$evidence/jobscheduler.txt" 2>&1 || true
  adb shell dumpsys notification > "$evidence/notifications.txt" 2>&1 || true
}

status=0
(
  cd "$GITHUB_WORKSPACE/app/android" || exit 1
  ./gradlew :app:connectedDebugAndroidTest --console=plain
) || status=$?

collect_evidence
exit "$status"
