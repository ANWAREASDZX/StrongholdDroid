#!/usr/bin/env bash
#
# docker/build.sh — convenience wrapper to run build_all.sh inside the
# Docker image. Useful for developers who don't want to install all the
# host-side toolchain packages.
#
# Usage:
#   scripts/docker/build.sh              # builds all native libs + APK (debug)
#   scripts/docker/build.sh release      # release variant
#   scripts/docker/build.sh ci           # CI variant (skips LTO for speed)
#
set -euo pipefail
cd "$(dirname "$0")/../.."   # project root

IMAGE="${STRONGHOLDDROID_IMAGE:-strongholddroid-builder}"
VARIANT="${1:-debug}"

# Build the image if missing
docker inspect "$IMAGE" &>/dev/null || \
    docker build -t "$IMAGE" -f scripts/docker/Dockerfile.build scripts/docker/

# Run the build, mounting the project at /work so artifacts land in the
# host's app/src/main/cpp/prebuilt/ directory.
docker run --rm \
    -v "$(pwd):/work" \
    -e TERM="$TERM" \
    -w /work \
    "$IMAGE" \
    bash -lc "
        set -e
        ./scripts/build_all.sh
        ./scripts/build_apk.sh $VARIANT
    "

echo
echo "Build artifacts:"
ls -lh app/build/outputs/apk/$VARIANT/*.apk 2>/dev/null || \
    echo "  (no APK — check the build log above)"
