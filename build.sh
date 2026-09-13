#!/usr/bin/env bash
# ساخت JB-DNS — ابزارها را با ./setup-tools.sh نصب کنید
set -e
TOOLS="${JB_DNS_TOOLS:-$HOME/.cache/jb-dns-tools}"
if [ ! -x "$TOOLS/jdk17/bin/java" ]; then
  echo "ابزارها پیدا نشدند. اول این را اجرا کنید:  ./setup-tools.sh" >&2
  exit 1
fi
export JAVA_HOME=/home/user/.cache/jb-dns-tools/jdk17
export ANDROID_HOME="$TOOLS/android-sdk"
export PATH="$JAVA_HOME/bin:$TOOLS/gradle-8.7/bin:$PATH"
exec gradle --no-daemon "$@"
