#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 HexRelic
# Builds the unsigned release APK twice, in two different folders, from clean copies of the
# source, and checks that both builds are byte-for-byte identical.
# Usage: tools/reproduce.sh            (run from the project root)
set -euo pipefail
src="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d /tmp/repro.XXXXXX)"
for n in first second-with-a-longer-path; do
  mkdir -p "$work/$n"
  tar -C "$src" --exclude=./build --exclude=./app/build --exclude=./core/build --exclude=./.gradle --exclude=./.kotlin --exclude=./release -cf - . | tar -C "$work/$n" -xf -
  (cd "$work/$n" && ./gradlew --no-daemon --no-build-cache -q :app:assembleRelease)
done
a="$work/first/app/build/outputs/apk/release/app-release-unsigned.apk"
b="$work/second-with-a-longer-path/app/build/outputs/apk/release/app-release-unsigned.apk"
sha256sum "$a" "$b"
if cmp -s "$a" "$b"; then echo "REPRODUCIBLE: both builds are identical"; else echo "NOT REPRODUCIBLE: builds differ"; exit 1; fi
