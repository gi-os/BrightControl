#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

unsigned=$1
signed=$2
apksigner="$ANDROID_HOME/build-tools/35.0.0/apksigner"
previous=$(tr -d ' :\r\n' < "$3" | tr 'A-F' 'a-f')
expected=$(tr -d ' :\r\n' < "$4" | tr 'A-F' 'a-f')

if [[ ! $previous =~ ^[0-9a-f]{64}$ || ! $expected =~ ^[0-9a-f]{64}$ || $previous == "$expected" ]]; then
  echo '::error::Pin distinct original and rotated release certificate fingerprints'
  exit 1
fi

# BrightControl requires API 29, so every supported device can use the rotated v3 signer.
"$apksigner" sign --out "$signed" --lineage "$SIGNING_LINEAGE_FILE" \
  --rotation-min-sdk-version 28 --v1-signing-enabled false --v2-signing-enabled false \
  --v3-signing-enabled true --v4-signing-enabled false \
  --ks "$KEYSTORE_FILE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD "$unsigned"

verification=$("$apksigner" verify --verbose --print-certs "$signed")
printf '%s\n' "$verification"
if ! grep -Fxq 'Verified using v3 scheme (APK Signature Scheme v3): true' <<< "$verification" ||
   ! grep -Fxq "Signer #1 certificate SHA-256 digest: $expected" <<< "$verification"; then
  echo '::error::APK must verify with the pinned rotated signer using signature scheme v3'
  exit 1
fi

lineage=$("$apksigner" lineage --in "$signed" --print-certs)
mapfile -t fingerprints < <(sed -nE 's/^Signer #[0-9]+ in lineage certificate SHA-256 digest: ([0-9a-f]{64})$/\1/p' <<< "$lineage")
if [[ ${#fingerprints[@]} -ne 2 || ${fingerprints[0]} != "$previous" || ${fingerprints[1]} != "$expected" ]]; then
  echo '::error::APK lineage must link the pinned original and rotated certificates'
  exit 1
fi

original=$(sed '/^Signer #2 in lineage certificate DN:/,$d' <<< "$lineage")
if ! grep -Fxq 'Has installed data capability: true' <<< "$original" ||
   ! grep -Eq '^Has rollback capability[[:space:]]*: false$' <<< "$original"; then
  echo '::error::APK lineage must preserve installed data without allowing signer rollback'
  exit 1
fi
printf '%s\n' "Verified APK signing lineage: $previous -> $expected"
