#!/usr/bin/env bash
# Regenerates Tests/Fixtures/*.json from the Kotlin implementation, which is the reference:
# android/dsp/src/test/kotlin/ch/lessnoise/dsp/golden/ (measuring core) and
# android/app/src/testPublic/kotlin/ch/lessnoise/app/golden/ (payloads, uploader, status, texts …).
# Needs the Android SDK (android/local.properties) and a JDK 17+. Review `git diff` afterwards,
# then run `swift test` here.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
export LESSNOISE_GOLDEN_DIR="$here/Tests/Fixtures"
mkdir -p "$LESSNOISE_GOLDEN_DIR"
cd "$here/../../android"
./gradlew --no-daemon \
  :dsp:test --tests 'ch.lessnoise.dsp.golden.*' \
  :app:testPublicDebugUnitTest --tests 'ch.lessnoise.app.golden.*'
echo "Fixtures written to $LESSNOISE_GOLDEN_DIR"
