#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 HexRelic
# Creates the release signing key. Run it ONCE, yourself, on a machine you trust.
#
#   tools/new-signing-key.sh /safe/place/witnessclip-release.p12
#
# keytool asks you to choose a password. It is typed at the prompt and is never written to
# any file by this script. Whoever holds this file and its password can publish updates in
# the app's name, and Android will refuse updates signed with any other key, so:
#   - keep at least two offline copies (for example two USB sticks in two places),
#   - never put the file inside the project folder or in the repository,
#   - if it is lost, existing users cannot be updated; if it leaks, the name is compromised.
set -euo pipefail
ks="${1:-}"
alias="${2:-witnessclip}"
[ -n "$ks" ] || { echo "usage: tools/new-signing-key.sh /path/outside/the/project/witnessclip-release.p12 [alias]"; exit 2; }
project="$(cd "$(dirname "$0")/.." && pwd)"
dir="$(cd "$(dirname "$ks")" && pwd)"
case "$dir/" in "$project"/*) echo "Refusing: keep the key outside the project folder ($project)."; exit 1;; esac
[ ! -e "$ks" ] || { echo "Refusing: $ks already exists. A signing key is never overwritten."; exit 1; }

# RSA 4096 with SHA-256, valid about 30 years: accepted by every Android version the app supports.
keytool -genkeypair -v -storetype PKCS12 -keystore "$ks" -alias "$alias" \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10950 \
  -dname "CN=WitnessClip"
chmod 600 "$ks"

echo
echo "Key created: $ks (alias: $alias)"
echo "Enter the password once more to display the certificate fingerprint:"
keytool -list -v -keystore "$ks" -alias "$alias" | grep -E "SHA-?256:" || true
echo
echo "Next: tools/sign-release.sh \"$ks\" $alias"
