#!/usr/bin/env bash
# Everything CI runs: ktlint, detekt, the tests, and the distribution the image packages.
# Usage: ./scripts/check.sh [extra Gradle arguments]
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew check installDist --continue "$@"
