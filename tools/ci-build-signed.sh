#!/usr/bin/env bash
set -euo pipefail
umask 077
export ANDROID_SIGNING_KEYSTORE_PATH="$RUNNER_TEMP/codex-pocket-release.p12"
printf '%s' "$ANDROID_SIGNING_KEYSTORE" | base64 --decode > "$ANDROID_SIGNING_KEYSTORE_PATH"
trap 'rm -f "$ANDROID_SIGNING_KEYSTORE_PATH"' EXIT
dist="$RUNNER_TEMP/codex-pocket-release"
upgrade="$RUNNER_TEMP/codex-pocket-upgrade"
mkdir -p "$dist" "$upgrade"
python3 -m pip install --quiet --disable-pip-version-check --target "$RUNNER_TEMP/ssh-fixture-deps" paramiko==4.0.0
PYTHONPATH="$RUNNER_TEMP/ssh-fixture-deps" python3 tools/ci-ssh-fixture.py generate app/src/androidTest/assets/ssh-fixture.json
./gradlew --no-daemon :app:assembleRelease :app:assembleReleaseAndroidTest
version="$(python3 -c 'import json;print(json.load(open("app/build/outputs/apk/release/output-metadata.json"))["elements"][0]["versionName"])')"
code="$(python3 -c 'import json;print(json.load(open("app/build/outputs/apk/release/output-metadata.json"))["elements"][0]["versionCode"])')"
cp app/build/outputs/apk/release/app-release.apk "$dist/CodexPocket-$version.apk"
cp "$dist/CodexPocket-$version.apk" "$upgrade/base.apk"
signer="$ANDROID_HOME/build-tools/35.0.0/apksigner"
"$signer" sign --ks "$ANDROID_SIGNING_KEYSTORE_PATH" --ks-key-alias "$ANDROID_SIGNING_KEY_ALIAS" --ks-pass env:ANDROID_SIGNING_STORE_PASSWORD --key-pass env:ANDROID_SIGNING_KEY_PASSWORD --out "$upgrade/android-test.apk" app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
./gradlew --no-daemon -PversionCode="$((code+1))" -PversionName="$version-upgrade-check" :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk "$upgrade/upgrade.apk"
expected="$(tr -d '\r\n ' < signing-certificate.sha256)"
for apk in "$upgrade/base.apk" "$upgrade/upgrade.apk" "$upgrade/android-test.apk"; do
  actual="$("$signer" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
  test "$actual" = "$expected"
done
(cd "$dist" && sha256sum ./*.apk > SHA256SUMS.txt)
echo 'Fixed-signature base, upgrade and instrumentation APKs verified'
