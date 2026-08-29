#!/usr/bin/env bash
#
# build_gl4es.sh — cross-compile gl4es (https://github.com/ptitSeb/gl4es)
# for Android arm64-v8a.
#
# gl4es is the *fallback* graphics path. When DXVK is unavailable
# (Vulkan driver missing or non-functional), we route wined3d's desktop
# OpenGL through gl4es, which translates GL 4.x → GLES 3.1+.
#
# Output:
#   prebuilt/arm64-v8a/libGL.so     ← Wine loads this as its libGL
#                                     (set LD_LIBRARY_PATH so wine64 finds it)
#
# Build time: ~4 min.
#
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"
print_banner "Building gl4es for Android arm64-v8a"

setup_android_env

GL4ES_VERSION="${GL4ES_VERSION:-v1.1.6}"
GL4ES_SRC="$BUILD_DIR/gl4es-$GL4ES_VERSION"
GL4ES_BUILD="$BUILD_DIR/gl4es-android-arm64"

if [[ ! -d "$GL4ES_SRC/.git" ]]; then
    log "Cloning gl4es $GL4ES_VERSION..."
    git clone --depth 1 --branch "$GL4ES_VERSION" \
        https://github.com/ptitSeb/gl4es.git "$GL4ES_SRC"
fi

mkdir -p "$GL4ES_BUILD"
cd "$GL4ES_BUILD"

log "Configuring gl4es..."
# ccache launcher flags are conditional — only when ccache is actually
# available (the CI Docker image ships it; bare dev boxes may not).
CCACHE_ARGS=()
if command -v ccache &>/dev/null; then
    CCACHE_ARGS+=(-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache)
fi

cmake "$GL4ES_SRC" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    "${CCACHE_ARGS[@]}" \
    -DANDROID_ABI="arm64-v8a" \
    -DANDROID_PLATFORM="android-26" \
    -DCMAKE_BUILD_TYPE=Release \
    -DGLX=OFF \
    -DEGL=ON \
    -DGBM=OFF \
    -DSTATICLIB=OFF \
    -DCMAKE_C_FLAGS="-O3 -fvisibility=hidden"

log "Compiling gl4es..."
make -j"$(nproc)"

# gl4es's CMake writes libGL.so.1 into the SOURCE tree's lib/ dir (not the
# build dir) — and produces no unversioned libGL.so at all.  Stage both
# names from the actual output location.
GL4ES_LIB="$GL4ES_SRC/lib/libGL.so.1"
[[ -f "$GL4ES_LIB" ]] || GL4ES_LIB="$GL4ES_BUILD/lib/libGL.so.1"
install_lib "$GL4ES_LIB" "libGL.so.1"
install_lib "$GL4ES_LIB" "libGL.so"

log_ok "gl4es built"
print_banner "gl4es build complete ✓"
