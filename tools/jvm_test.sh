#!/usr/bin/env bash
#
# Compile and run the Android module's unit tests without Gradle.
#
# Why this exists: Gradle needs a full Android SDK, a matching JDK and network
# access to a plugin portal. Where those are unavailable, the whole 366-test
# suite was previously unreachable and only a hand-written JUnit shim could run
# a subset of pure-Java classes -- which compiles nothing that touches Android,
# so most of the app went unverified.
#
# This gets the same jars Gradle would and drives javac directly. It is not a
# replacement for `gradlew testDebugUnitTest`; it is what to run when that
# cannot start. It builds no APK and proves nothing about a device.
#
# Two details that matter and are easy to get wrong:
#
#   1. android.jar contains STUBS. Every method throws "RuntimeException:
#      Stub!". Anything the tests genuinely execute -- org.json in particular
#      -- must come from a real implementation placed EARLIER on the classpath,
#      or tests fail for reasons that have nothing to do with the code.
#
#   2. Some tests read source files from disk and expect the Gradle module
#      directory as the working directory. Run from anywhere else and they fail
#      spuriously. This script cds there.
#
# Usage:
#   tools/jvm_test.sh              # fetch deps if needed, compile, run all
#   tools/jvm_test.sh <TestClass>  # run one, e.g. com.mrnobody.util.HostsTest
#
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$REPO/app/android/app"
WORK="${MRNOBODY_JVM_WORK:-/tmp/mrnobody-jvm}"
JARS="$WORK/jars"
SDK="$WORK/sdk"
OUT="$WORK/out"
TOUT="$WORK/tout"
GEN="$WORK/gen"

PLATFORM_ZIP="platform-34-ext12_r01.zip"
PLATFORM_DIR="$SDK/platforms/android-34-ext12"
ANDROID_JAR="$PLATFORM_DIR/android.jar"

mkdir -p "$JARS" "$SDK" "$OUT" "$TOUT" "$GEN"

fetch() { # url dest
  [ -s "$2" ] && return 0
  echo "  fetching $(basename "$2")"
  curl -sSL --fail -o "$2" "$1" || { echo "FAILED: $1" >&2; return 1; }
}

aar_classes() { # name  (extracts classes.jar from an .aar)
  local n="$1"
  [ -s "$JARS/$n-classes.jar" ] && return 0
  ( cd "$JARS" && mkdir -p "ex_$n" && cd "ex_$n" \
    && unzip -q -o "../$n.aar" classes.jar && mv classes.jar "../$n-classes.jar" )
}

echo "==> dependencies"
G=https://maven.google.com
M=https://repo1.maven.org/maven2

# A real org.json. MUST precede android.jar on the classpath (see note 1).
fetch "$M/org/json/json/20240303/json-20240303.jar"                 "$JARS/json-20240303.jar"
fetch "$M/junit/junit/4.13.2/junit-4.13.2.jar"                      "$JARS/junit-4.13.2.jar"
fetch "$M/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"     "$JARS/hamcrest-core-1.3.jar"
fetch "$M/com/google/guava/guava/33.0.0-android/guava-33.0.0-android.jar" "$JARS/guava-33.jar"

# annotation-jvm, not annotation: the latter is a metadata-only artifact.
fetch "$G/androidx/annotation/annotation-jvm/1.7.1/annotation-jvm-1.7.1.jar" "$JARS/annotation-jvm-1.7.1.jar"
fetch "$G/androidx/lifecycle/lifecycle-common/2.7.0/lifecycle-common-2.7.0.jar" "$JARS/lifecycle-common-2.7.0.jar"
fetch "$G/androidx/concurrent/concurrent-futures/1.1.0/concurrent-futures-1.1.0.jar" "$JARS/concurrent-futures-1.1.0.jar"

for spec in \
  "androidx/webkit/webkit/1.11.0/webkit-1.11.0.aar|webkit-1.11.0" \
  "androidx/swiperefreshlayout/swiperefreshlayout/1.1.0/swiperefreshlayout-1.1.0.aar|swiperefreshlayout-1.1.0" \
  "androidx/work/work-runtime/2.9.0/work-runtime-2.9.0.aar|work-runtime-2.9.0" \
  "androidx/core/core/1.13.1/core-1.13.1.aar|core-1.13.1" \
  "androidx/lifecycle/lifecycle-runtime/2.7.0/lifecycle-runtime-2.7.0.aar|lifecycle-runtime-2.7.0" \
  "androidx/lifecycle/lifecycle-livedata-core/2.7.0/lifecycle-livedata-core-2.7.0.aar|lifecycle-livedata-core-2.7.0"
do
  path="${spec%%|*}"; name="${spec##*|}"
  fetch "$G/$path" "$JARS/$name.aar"
  aar_classes "$name"
done

FLUTTER_V="1.0.0-f6344b75dcf861d8bf1f1322780b8811f982e31a"
fetch "https://storage.googleapis.com/download.flutter.io/io/flutter/flutter_embedding_release/$FLUTTER_V/flutter_embedding_release-$FLUTTER_V.jar" \
      "$JARS/flutter_embedding.jar"

if [ ! -s "$ANDROID_JAR" ]; then
  echo "==> android platform"
  fetch "https://dl.google.com/android/repository/$PLATFORM_ZIP" "$WORK/$PLATFORM_ZIP"
  mkdir -p "$SDK/platforms"
  unzip -q -o "$WORK/$PLATFORM_ZIP" -d "$SDK/platforms/"
fi

echo "==> R.java (AAPT generates the real one during a Gradle build)"
python3 - "$MODULE/src/main/res" "$GEN" <<'PY'
import re, sys, pathlib
res, gen = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
strings=colors=styles=arrays=None
strings, colors, styles, arrays = set(), set(), set(), set()
for f in res.rglob("values*/*.xml"):
    t = f.read_text(encoding="utf-8")
    strings |= set(re.findall(r'<string\s+name="([^"]+)"', t))
    colors  |= set(re.findall(r'<color\s+name="([^"]+)"', t))
    styles  |= set(re.findall(r'<style\s+name="([^"]+)"', t))
    arrays  |= set(re.findall(r'<(?:string-array|integer-array|array)\s+name="([^"]+)"', t))
drawables = {p.stem for d in res.glob("drawable*") for p in d.glob("*")}
mipmaps   = {p.stem for d in res.glob("mipmap*") for p in d.glob("*")}
def block(name, items, base):
    if not items: return ""
    out = [f"    public static final class {name} {{"]
    out += [f"        public static final int {k.replace('.','_')}=0x{base+i:08x};"
            for i, k in enumerate(sorted(items))]
    return "\n".join(out + ["    }"])
parts = ["package com.mrnobody.browser;", "",
         "/** Generated for offline compilation only. */", "public final class R {"]
for n, (items, base) in {"string":(strings,0x7f010000), "color":(colors,0x7f020000),
                         "style":(styles,0x7f030000),  "array":(arrays,0x7f040000),
                         "drawable":(drawables,0x7f050000), "mipmap":(mipmaps,0x7f060000)}.items():
    b = block(n, items, base)
    if b: parts.append(b)
parts.append("}")
d = gen / "com/mrnobody/browser"; d.mkdir(parents=True, exist_ok=True)
(d / "R.java").write_text("\n".join(parts))
print(f"    {len(strings)} strings, {len(drawables)} drawables, {len(mipmaps)} mipmaps")
PY

# json first, android.jar after: real classes win over the throwing stubs.
CP="$JARS/json-20240303.jar:$(ls "$JARS"/*.jar | grep -v json-20240303 | tr '\n' ':')$ANDROID_JAR"

echo "==> compiling main"
rm -rf "$OUT"; mkdir -p "$OUT"
( cd "$MODULE/src/main/java" && javac -encoding UTF-8 -nowarn -proc:none \
    -cp "$CP" -d "$OUT" $(find . -name '*.java') "$GEN/com/mrnobody/browser/R.java" )
echo "    $(find "$OUT" -name '*.class' | wc -l) classes"

echo "==> compiling tests"
rm -rf "$TOUT"; mkdir -p "$TOUT"
( cd "$MODULE/src/test/java" && javac -encoding UTF-8 -nowarn -proc:none \
    -cp "$CP:$OUT" -d "$TOUT" $(find . -name '*.java') )
echo "    $(find "$TOUT" -name '*.class' | wc -l) classes"

echo "==> running"
if [ $# -gt 0 ]; then
  CLASSES="$*"
else
  CLASSES=$(cd "$TOUT" && find . -name '*Test.class' \
            | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
fi
# Module dir as cwd: some tests read source files relative to it (note 2).
cd "$MODULE"
java -cp "$CP:$OUT:$TOUT" org.junit.runner.JUnitCore $CLASSES
