#!/usr/bin/env bash
# Baut den Tablet-Agent (Release, signiert) und legt ihn nach android/dist/,
# von wo der tabletviewer ihn unter /app/tablet-agent.apk ausliefert.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/opt/jdk21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
[ -f keystore.properties ] || { echo "FEHLER: keystore.properties fehlt (siehe README)"; exit 1; }
./gradlew -q assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/tablet-agent.apk
"$ANDROID_HOME/build-tools/34.0.0/aapt2" dump badging dist/tablet-agent.apk | python3 -c '
import json, re, sys, hashlib
b = sys.stdin.read()
m = re.search(r"versionCode=.(\d+). versionName=.([^\x27]+).", b)
h = hashlib.sha256(open("dist/tablet-agent.apk", "rb").read()).hexdigest()
json.dump({"versionCode": int(m.group(1)), "versionName": m.group(2), "sha256": h},
          open("dist/tablet-agent.json", "w"))
print("dist/tablet-agent.apk", m.group(2), "(" + m.group(1) + ")")'
