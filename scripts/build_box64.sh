#!/usr/bin/env bash
#
# build_box64.sh — compile Box64 (https://github.com/ptitSeb/box64)
# for Android arm64-v8a. Box64 is the x86_64 → ARM64 binary translator
# that StrongholdDroid uses to run wine64 (which itself is an x86_64 ELF).
#
# Key flags:
#   -DANDROID=ON            — enable Android-specific dladdr workarounds
#   -DARM_DYNAREC=ON        — enable NEON-based dynarec (huge perf gain)
#   -DWITH_DYNAREC=ON       — master switch for the dynarec
#   -DSTATIC_BUILD=ON       — produce libbox64.so so we can dlopen from
#                              JNI instead of fork/exec'ing box64 directly
#
# Build time: ~5 min on a 16-core box.
#
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"
print_banner "Building Box64 for Android arm64-v8a"

setup_android_env

BOX64_VERSION="${BOX64_VERSION:-v0.4.4}"
BOX64_SRC="$BUILD_DIR/box64-$BOX64_VERSION"
BOX64_BUILD="$BUILD_DIR/box64-android-arm64"

if [[ ! -d "$BOX64_SRC/.git" ]]; then
    log "Cloning Box64 $BOX64_VERSION..."
    git clone --depth 1 --branch "$BOX64_VERSION" \
        https://github.com/ptitSeb/box64.git "$BOX64_SRC"
fi

mkdir -p "$BOX64_BUILD"
cd "$BOX64_BUILD"

log "Configuring Box64..."
cmake "$BOX64_SRC" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DCMAKE_C_COMPILER_LAUNCHER=ccache \
    -DCMAKE_CXX_COMPILER_LAUNCHER=ccache \
    -DANDROID_ABI="arm64-v8a" \
    -DANDROID_PLATFORM="android-26" \
    -DCMAKE_BUILD_TYPE=Release \
    -DANDROID=ON \
    -DARM_DYNAREC=ON \
    -DWITH_DYNAREC=ON \
    -DSTATIC_BUILD=ON \
    -DNOGIT=ON \
    -DCMAKE_C_FLAGS="-O3 -fvisibility=hidden -fvisibility-inlines-hidden" \
    -DCMAKE_CXX_FLAGS="-O3 -fvisibility=hidden"

log "Compiling Box64..."
make -j"$(nproc)"

log "Staging..."
install_lib "$BOX64_BUILD/libbox64.so" "libbox64.so"
install_lib "$BOX64_BUILD/box64"        "bin/box64" 2>/dev/null || true

# Print the box64 version so CI logs include it
"$BOX64_BUILD/box64" --version 2>&1 | head -1 || warn "could not run box64 --version"

log_ok "Box64 built: libbox64.so"
print_banner "Box64 build complete ✓"
