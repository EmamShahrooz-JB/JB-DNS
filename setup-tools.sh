#!/usr/bin/env bash
#
# نصب خودکار ابزارهای ساخت JB-DNS در ~/.cache/jb-dns-tools
# (JDK 17 + Android SDK platform 34 + build-tools 34 + Gradle 8.7)
#
# نکته: عمداً `set -e` نداریم؛ بعضی گام‌ها (مثل پذیرش مجوزها) کد خروج
# غیرصفر می‌دهند در حالی که کارشان درست انجام شده است.
set -uo pipefail

TOOLS="${JB_DNS_TOOLS:-$HOME/.cache/jb-dns-tools}"
SDK="$TOOLS/android-sdk"
ROOT="$(cd "$(dirname "$0")" && pwd)"
fail=0

mkdir -p "$TOOLS"
cd "$TOOLS" || exit 1

echo "==> JDK 17 (Temurin)"
if [ ! -x "$TOOLS/jdk17/bin/java" ]; then
    curl -fsSL -o jdk17.tar.gz \
        "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" || fail=1
    tar xzf jdk17.tar.gz && rm -f jdk17.tar.gz
    mv jdk-17* jdk17
fi
[ -x "$TOOLS/jdk17/bin/java" ] || { echo "!! JDK نصب نشد"; fail=1; }

echo "==> Gradle 8.7"
if [ ! -d "$TOOLS/gradle-8.7" ]; then
    curl -fsSL -o gradle.zip "https://services.gradle.org/distributions/gradle-8.7-bin.zip" || fail=1
    unzip -q gradle.zip && rm -f gradle.zip
fi

echo "==> Android command-line tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    curl -fsSL -o cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" || fail=1
    unzip -q cmdline-tools.zip && rm -f cmdline-tools.zip
    mkdir -p "$SDK/cmdline-tools"
    mv cmdline-tools "$SDK/cmdline-tools/latest"
fi

export JAVA_HOME="$TOOLS/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> پذیرش مجوزها (خطای احتمالی بی‌خطر است)"
yes 2>/dev/null | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses > /dev/null 2>&1

echo "==> نصب platforms;android-34 و build-tools;34.0.0"
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
    "platforms;android-34" "build-tools;34.0.0" 2>&1 | tr '\r' '\n' | grep -vE '^\[|^Loading|^$' | tail -5

if [ ! -d "$SDK/platforms/android-34" ] || [ ! -d "$SDK/build-tools/34.0.0" ]; then
    echo "!! پکیج‌های SDK کامل نصب نشدند"; fail=1
fi

echo "==> نوشتن local.properties"
echo "sdk.dir=$SDK" > "$ROOT/local.properties"
sed -i "s|^export JAVA_HOME=.*|export JAVA_HOME=$TOOLS/jdk17|;s|/home/user/JB-DNS/.tools|$TOOLS|g" "$ROOT/build.sh" 2>/dev/null

echo
if [ "$fail" -ne 0 ]; then
    echo "❌ نصب کامل نشد — خروجی بالا را ببینید"
    exit 1
fi
echo "✅ آماده است:"
echo "   ./build.sh :app:testDebugUnitTest"
echo "   ./build.sh :app:assembleRelease"
