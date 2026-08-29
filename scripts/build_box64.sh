#!/usr/bin/env bash
#
# build_box64.sh — build box64's WoW64 cpu dll (wowbox64.dll) for the
# "Arm64 Wine WOW64" runtime.
#
# StrongholdDroid does NOT run wine under the box64 Linux executable.
# Instead, wine (built natively for Android arm64 by build_wine.sh with
# --enable-archs=aarch64,i386) runs 32-bit x86 Windows code through the
# WoW64 cpu dll — box64's wowbox64.dll (an aarch64-windows PE dll that
# embeds box64's x86 emulator + arm64 dynarec).  Wine loads it under the
# name "xtajit.dll" (see wine's dlls/wow64/syscall.c get_cpu_dll_name()).
#
# We therefore only build box64's `wowbox64` ExternalProject target —
# the box64 Linux executable itself is neither needed nor shipped.
#
# Requirements: aarch64-w64-mingw32-clang on PATH (llvm-mingw; see
# common.sh setup_android_env + Dockerfile.build).
#
# Build time: ~10 min on a 16-core box (dynarec passes are heavy).
#
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"
print_banner "Building box64 WoW64 cpu dll (wowbox64.dll)"

setup_android_env

BOX64_VERSION="${BOX64_VERSION:-v0.4.4}"
BOX64_SRC="$BUILD_DIR/box64-$BOX64_VERSION"
BOX64_BUILD="$BUILD_DIR/box64-android-arm64"

if [[ ! -d "$BOX64_SRC/.git" ]]; then
    log "Cloning Box64 $BOX64_VERSION..."
    git clone --depth 1 --branch "$BOX64_VERSION" \
        https://github.com/ptitSeb/box64.git "$BOX64_SRC"
fi

# ---- Android/toolchain-specific patches --------------------------------------
apply_patches() {
    local pdir="$ROOT_DIR/scripts/patches/box64"
    if [[ -d "$pdir" ]]; then
        for p in "$pdir"/*.patch; do
            [[ -e "$p" ]] || continue
            if ( cd "$BOX64_SRC" && git apply --check "$p" ) 2>/dev/null; then
                log "  applying patch: $(basename "$p")"
                ( cd "$BOX64_SRC" && git apply --whitespace=fix "$p" ) \
                    || die "patch failed to apply: $p"
            elif ( cd "$BOX64_SRC" && git apply --reverse --check "$p" ) 2>/dev/null; then
                log "  patch already applied: $(basename "$p") (skipping)"
            else
                die "patch does not apply cleanly: $p (source tree drifted — nuke \$BUILD_DIR/box64-$BOX64_VERSION and retry)"
            fi
        done
    fi
}
apply_patches

# box64's wow64 subproject needs a python3 interpreter (it generates
# dynacache hash headers) and aarch64-w64-mingw32-{clang,dlltool} from
# llvm-mingw (already on PATH via setup_android_env).
command -v python3 &>/dev/null || die "python3 not found (needed by box64's wow64 build)"
command -v aarch64-w64-mingw32-clang &>/dev/null \
    || die "aarch64-w64-mingw32-clang not found — install llvm-mingw (see Dockerfile.build / common.sh)"

mkdir -p "$BOX64_BUILD"
cd "$BOX64_BUILD"

# Resume-safety: re-running cmake in a configured dir is fine (it just
# re-checks the cache), so no guard needed here — `cmake` then `make
# wowbox64` both resume incrementally.
log "Configuring box64 (WOW64 + ARM_DYNAREC)..."
cmake "$BOX64_SRC" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="arm64-v8a" \
    -DANDROID_PLATFORM="android-26" \
    -DCMAKE_BUILD_TYPE=Release \
    -DWOW64=ON \
    -DARM_DYNAREC=ON \
    -DNOGIT=ON \
    -DCMAKE_C_FLAGS="-O2 -fvisibility=hidden" \
    -DCMAKE_CXX_FLAGS="-O2 -fvisibility=hidden"

# Build ONLY the wow64 ExternalProject — not the box64 Linux executable
# (which our runtime never launches).  ExternalProject_Add(wowbox64 ...)
# creates a top-level make target of the same name.
log "Compiling wowbox64 (box64 WoW64 cpu dll)..."
make -j"$(nproc)" wowbox64

# The ExternalProject builds in <build>/wowbox64-prefix/src/wowbox64-build/
WOW64_DLL="$(find "$BOX64_BUILD/wowbox64-prefix" -name wowbox64.dll -type f 2>/dev/null | head -1)"
[[ -n "$WOW64_DLL" ]] || die "wowbox64.dll was not produced — check $BOX64_BUILD/wowbox64-prefix/src/wowbox64-build/"

install_lib "$WOW64_DLL" "wow64/wowbox64.dll"

log_ok "box64 WoW64 cpu dll: $(du -h "$PREBUILT_DIR/arm64-v8a/wow64/wowbox64.dll" | cut -f1)"
log_ok "  (staged at runtime as xtajit.dll — wine's arm64 wow64 cpu dll name)"
print_banner "Box64 WoW64 build complete ✓"
