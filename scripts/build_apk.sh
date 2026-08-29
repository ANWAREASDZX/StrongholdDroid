#!/usr/bin/env bash
#
# build_apk.sh — assemble the StrongholdDroid APK after build_all.sh
# has populated app/src/main/cpp/prebuilt/.
#
# This script ALSO packages the wine runtime tree into
# app/src/main/assets/prebuilt.zip — the APK carries the entire runtime
# (wine loader, wineserver, builtin DLLs, box64 wow64 cpu dll, gl4es,
# libpulse stub, DXVK) as a compressed asset that EnvironmentBuilder
# extracts into filesDir on first run (java.util.zip.ZipInputStream —
# no extra dependencies needed).
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
EXPECTED_FILES=(
    libpulse.so
    libGL.so
    libwine/wine
    libwine/wineserver
    wow64/wowbox64.dll
    wine_dlls/aarch64-windows/kernel32.dll
    wine_dlls/i386-windows/kernel32.dll
    wine_dlls/aarch64-unix/ntdll.so
    wine_dlls/aarch64-unix/winex11.so
    x11-libs/libX11.so
)
for f in "${EXPECTED_FILES[@]}"; do
    if [[ ! -f "$PREBUILT_DIR/arm64-v8a/$f" ]]; then
        die "Missing prebuilt: $f. Run ./scripts/build_all.sh first."
    fi
done
log_ok "All prebuilt libs present"

# ---- Package the wine runtime into assets/prebuilt.zip ---------------------
# Runtime layout inside the zip (= filesDir layout after extraction):
#   usr/bin/{wine,wineserver,wineboot,...}
#   usr/lib/libpulse.so.0, libpulsecommon-*.so, libGL.so.1
#   usr/lib/wine/{aarch64-unix,aarch64-windows,i386-windows}/...
#   usr/share/wine/nls/*.nls
#   wow64/wowbox64.dll          (staged as xtajit.dll into the prefix)
#   dxvk-wine-dlls/*.dll        (32-bit DXVK, staged into syswow64)
ASSETS_DIR="$ROOT_DIR/app/src/main/assets"
RUNTIME_STAGE="$BUILD_DIR/runtime-stage"
log "Packaging wine runtime into assets/prebuilt.zip ..."
rm -rf "$RUNTIME_STAGE"
mkdir -p "$RUNTIME_STAGE/usr/bin" "$RUNTIME_STAGE/usr/lib/wine" \
         "$RUNTIME_STAGE/usr/share" "$RUNTIME_STAGE/wow64" \
         "$RUNTIME_STAGE/dxvk-wine-dlls"

PRE="$PREBUILT_DIR/arm64-v8a"

# 1. Wine binaries — the whole arm64-v8a/bin (wine, wineserver, wineboot,
#    winecfg, msiexec, regedit, ... all arm64 ELF, all potentially useful).
cp -f "$PRE/usr/arm64-v8a/bin/"* "$RUNTIME_STAGE/usr/bin/" 2>/dev/null || true
[[ -f "$RUNTIME_STAGE/usr/bin/wine" ]] || die "wine binary missing from install tree"

# 2. Runtime shared libraries (linked/dlopened by wine at runtime).
#    NOTE: use cp -L (dereference symlinks) and search BOTH layouts —
#    build_pulse.sh installs into lib/ and adds convenience symlinks at
#    the parent dir; a plain -f test on a symlink fails when only the
#    link exists in one location, which silently dropped libpulse.so.0
#    from v0.1.0's runtime (broke winepulse audio).
copy_runtime_lib() {  # copy_runtime_lib <libname>
    local f="$1"
    for cand in "$PRE/lib/$f" "$PRE/$f" \
                "$PRE/lib/${f%.so}.so.0" "$PRE/${f%.so}.so.0"; do
        if [[ -e "$cand" ]]; then
            cp -fL "$cand" "$RUNTIME_STAGE/usr/lib/$f"
            return 0
        fi
    done
    warn "  runtime lib not found anywhere: $f"
    return 1
}
for f in libpulse.so.0 libpulse-simple.so.0 libGL.so.1 libpulse.so; do
    copy_runtime_lib "$f" || true
done
for f in "$PRE/lib/"libpulsecommon-*.so "$PRE/"libpulsecommon-*.so; do
    [[ -e "$f" ]] && cp -fL "$f" "$RUNTIME_STAGE/usr/lib/" && break
done
# Also ship the pkg-config-free linker name libpulse.so.0 under its plain
# SONAME — winepulse.so records NEEDED libpulse.so.0.
for f in "$PRE/lib/"libpulse.so.0* "$PRE/"libpulse.so.0*; do
    [[ -e "$f" ]] && cp -fL "$f" "$RUNTIME_STAGE/usr/lib/libpulse.so.0" && break
done

# 2b. X11 client libraries — winex11.so NEEDs libX11.so/libXext.so/... at
#     dlopen time; the app sets LD_LIBRARY_PATH=<filesDir>/usr/lib.
X11_LIB_COUNT=0
if [[ -d "$PRE/x11-libs" ]]; then
    for f in "$PRE/x11-libs/"*.so; do
        [[ -e "$f" ]] && { cp -f "$f" "$RUNTIME_STAGE/usr/lib/"; X11_LIB_COUNT=$((X11_LIB_COUNT+1)); }
    done
fi
log_ok "  X11 client libs packaged: $X11_LIB_COUNT"
[[ "$X11_LIB_COUNT" -ge 10 ]] || die "too few X11 libs ($X11_LIB_COUNT < 10) — winex11.drv cannot load"

# 3. Wine builtin DLLs + unix libs (from the staged wine_dlls tree).
cp -af "$PRE/wine_dlls/aarch64-unix"     "$RUNTIME_STAGE/usr/lib/wine/"
cp -af "$PRE/wine_dlls/aarch64-windows"  "$RUNTIME_STAGE/usr/lib/wine/"
cp -af "$PRE/wine_dlls/i386-windows"     "$RUNTIME_STAGE/usr/lib/wine/"

# 4. NLS data + FONTS (wine loads locale/codepage tables and its bitmap
#    fonts from here at runtime — without fonts, wine message boxes and
#    game dialogs render no text at all).
cp -af "$PRE/usr/share/wine/nls" "$RUNTIME_STAGE/usr/share/wine/" 2>/dev/null \
    || warn "wine nls dir not found in install tree"
if [[ -d "$PRE/usr/share/wine/fonts" ]]; then
    cp -af "$PRE/usr/share/wine/fonts" "$RUNTIME_STAGE/usr/share/wine/"
    log_ok "  wine fonts packaged: $(ls "$RUNTIME_STAGE/usr/share/wine/fonts" | wc -l) files"
else
    warn "wine fonts dir not found in install tree — wine dialogs will show no text"
fi

# 5. box64 wow64 cpu dll + DXVK dlls.
cp -f "$PRE/wow64/wowbox64.dll" "$RUNTIME_STAGE/wow64/"
cp -f "$PRE/dxvk-wine-dlls/"*.dll "$RUNTIME_STAGE/dxvk-wine-dlls/" 2>/dev/null || true

# 6. Zip it (deflate).  Prefer `zip`; fall back to the JDK's `jar`.
mkdir -p "$ASSETS_DIR"
if command -v zip &>/dev/null; then
    ( cd "$RUNTIME_STAGE" && zip -q -r -9 "$ASSETS_DIR/prebuilt.zip" . )
elif command -v jar &>/dev/null; then
    ( cd "$RUNTIME_STAGE" && jar -cMf "$ASSETS_DIR/prebuilt.zip" . )
else
    die "neither 'zip' nor 'jar' found — cannot package runtime assets"
fi
log_ok "runtime asset: $(du -h "$ASSETS_DIR/prebuilt.zip" | cut -f1) ($(du -sh "$RUNTIME_STAGE" | cut -f1) uncompressed)"

# ---- JDK check -------------------------------------------------------------
if ! command -v java &>/dev/null; then
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
        # signingConfig is applied conditionally in build.gradle.kts —
        # with signing env vars set, output is `app-release.apk` (signed);
        # without them the config falls back to the debug keystore so the
        # release variant always produces an installable APK.
        ./gradlew :app:assembleRelease --no-daemon --console=plain
        APK_CANDIDATES=(
            "app/build/outputs/apk/release/app-release.apk"
            "app/build/outputs/apk/release/app-universal-release.apk"
            "app/build/outputs/apk/release/app-arm64-v8a-release.apk"
            "app/build/outputs/apk/release/app-release-unsigned.apk"
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
