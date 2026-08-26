#!/usr/bin/env bash
#
# build_apk.sh — assemble the StrongholdDroid APK after build_all.sh
# has populated app/src/main/cpp/prebuilt/.
#
# Usage:
#   ./scripts/build_apk.sh [debug|release|ci]
#
# Outputs the APK to:
#   app/build/outputs/apk/<variant>/app-<variant>.apk
#
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"

VARIANT="${1:-debug}"
print_banner "Building APK ($VARIANT)"

# ---- Sanity check: prebuilt libs must exist --------------------------------
EXPECTED_LIBS=(libwine.so libbox64.so libpulse.so libGL.so libdxvk_loader.so)
for lib in "${EXPECTED_LIBS[@]}"; do
    if [[ ! -f "$PREBUILT_DIR/arm64-v8a/$lib" ]]; then
        die "Missing prebuilt: $lib. Run ./scripts/build_all.sh first."
    fi
done
log_ok "All prebuilt libs present"

# ---- JDK check -------------------------------------------------------------
if ! command -v java &> /dev/null; then
    die "Java/JDK not on PATH — install JDK 17."
fi

# ---- Gradle wrapper bootstrap ---------------------------------------------
if [[ ! -x "$ROOT_DIR/gradlew" ]]; then
    log "Bootstrapping Gradle wrapper..."
    cat > "$ROOT_DIR/gradlew" <<'WRAPPER'
#!/bin/sh
exec java -cp "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
WRAPPER
    chmod +x "$ROOT_DIR/gradlew"
fi

# ---- Build ----------------------------------------------------------------
# AGP 8.5.2 with `splits.abi.isUniversalApk = true` produces BOTH a
# universal APK and per-ABI APKs. The exact filename depends on:
#   - signing config (signed vs unsigned → different suffix)
#   - AGP version (universal suffix conventions changed in 8.x)
# Try a list of candidate names instead of hard-coding one — first match wins.
cd "$ROOT_DIR"
case "$VARIANT" in
    debug)
        ./gradlew :app:assembleDebug --no-daemon --console=plain
        APK_CANDIDATES=(
            "app/build/outputs/apk/debug/app-debug.apk"
            "app/build/outputs/apk/debug/app-universal-debug.apk"
            "app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
        )
        ;;
    release)
        # signingConfig is always applied (build.gradle.kts L83). With
        # signing env vars set, output is `app-release.apk` (signed).
        # Without signing, AGP keeps the `app-release-unsigned.apk` name.
        ./gradlew :app:assembleRelease --no-daemon --console=plain
        APK_CANDIDATES=(
            "app/build/outputs/apk/release/app-release.apk"
            "app/build/outputs/apk/release/app-release-unsigned.apk"
            "app/build/outputs/apk/release/app-universal-release.apk"
            "app/build/outputs/apk/release/app-universal-release-unsigned.apk"
            "app/build/outputs/apk/release/app-arm64-v8a-release.apk"
        )
        ;;
    ci)
        ./gradlew :app:assembleCi --no-daemon --console=plain \
            --no-build-cache --no-configuration-cache
        APK_CANDIDATES=(
            "app/build/outputs/apk/ci/app-ci.apk"
            "app/build/outputs/apk/ci/app-universal-ci.apk"
            "app/build/outputs/apk/ci/app-arm64-v8a-ci.apk"
        )
        ;;
    *)
        die "Unknown variant: $VARIANT (use debug|release|ci)"
        ;;
esac

APK_OUT=""
for c in "${APK_CANDIDATES[@]}"; do
    if [[ -f "$c" ]]; then
        APK_OUT="$c"
        break
    fi
done
[[ -n "$APK_OUT" ]] || die "APK not produced (tried: ${APK_CANDIDATES[*]})"

APK_SIZE=$(du -h "$APK_OUT" | cut -f1)
log_ok "Built: $APK_OUT ($APK_SIZE)"

# Print a one-line install hint
log "Install with: adb install -r $ROOT_DIR/$APK_OUT"
