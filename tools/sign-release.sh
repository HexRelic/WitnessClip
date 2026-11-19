#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 HexRelic
# Builds the release APK from clean, signs it and checks the result.
#
#   tools/sign-release.sh /safe/place/witnessclip-release.p12 [alias]
#
# The keystore password is asked at the prompt by apksigner. For unattended use it can be
# given in the environment variable KS_PASS instead; it is never written to a file or shown.
# The signature is added to a COPY: the unsigned APK stays as built, so anyone can rebuild
# it and compare (see docs/REPRODUCIBLE.md).
set -euo pipefail
ks="${1:-}"
alias="${2:-witnessclip}"
[ -n "$ks" ] && [ -f "$ks" ] || { echo "usage: tools/sign-release.sh /path/to/keystore [alias]"; exit 2; }
ks="$(cd "$(dirname "$ks")" && pwd)/$(basename "$ks")"
project="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] && [ -f local.properties ]; then sdk="$(sed -n 's/^sdk\.dir=//p' local.properties)"; fi
bt="$sdk/build-tools/36.0.0"
[ -x "$bt/apksigner" ] || { echo "apksigner not found in $bt (set ANDROID_HOME)"; exit 1; }

version="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
code="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)"
unsigned="app/build/outputs/apk/release/app-release-unsigned.apk"
out="release/$version"
signed="$out/WitnessClip-$version.apk"

echo "== 1/5 clean build of version $version (code $code)"
./gradlew --no-daemon --no-build-cache -q clean :core:test :app:assembleRelease

echo "== 2/5 alignment"
"$bt/zipalign" -c -P 16 4 "$unsigned"

echo "== 3/5 signing (APK signature schemes v2 and v3)"
mkdir -p "$out"
cp "$unsigned" "$out/WitnessClip-$version-unsigned.apk"
pass=()
if [ -n "${KS_PASS:-}" ]; then pass=(--ks-pass env:KS_PASS); fi
# v1 (JAR) signing is not needed from Android 9, the oldest version the app supports.
"$bt/apksigner" sign --ks "$ks" --ks-key-alias "$alias" "${pass[@]}" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
  --out "$signed" "$unsigned"

echo "== 4/5 verification"
certs="$("$bt/apksigner" verify --verbose --print-certs --min-sdk-version 28 "$signed")"
echo "$certs" | grep -E "Verified using|certificate SHA-256"
fingerprint="$(echo "$certs" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
./gradlew --no-daemon -q :app:checkApkStructure -Papk="$project/$signed"

echo "== 5/5 checksums"
(cd "$out" && sha256sum "WitnessClip-$version-unsigned.apk" "WitnessClip-$version.apk" > SHA256SUMS.txt && cat SHA256SUMS.txt)

echo
echo "Signed APK:               $signed"
echo "Signing certificate:      $fingerprint"
if grep -q "\"$fingerprint\"" core/src/main/kotlin/evidence/core/Release.kt; then
  echo "This certificate is the one pinned in the verifier (Release.kt)."
else
  echo "NOT YET PINNED. Before publishing, put this value in:"
  echo "  - core/src/main/kotlin/evidence/core/Release.kt   (SIGNERS)"
  echo "  - docs/REPRODUCIBLE.md                             (Signing certificate)"
  echo "  - the F-Droid metadata                             (AllowedAPKSigningKeys)"
  echo "then run this script again, because the pinned value is part of the app."
fi
