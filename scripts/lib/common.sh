#!/usr/bin/env bash
# lib/common.sh — shared bash helpers for StrongholdDroid build scripts.
# Source from any script via:  source "$(dirname "$0")/lib/common.sh"

set -euo pipefail

# ---- Output formatting ------------------------------------------------------
RED=$'\033[1;31m'
GRN=$'\033[1;32m'
YEL=$'\033[1;33m'
CYN=$'\033[1;36m'
RST=$'\033[0m'

log()     { printf "${CYN}[STR]*${RST} %s\n" "$*"; }
log_ok()  { printf "${GRN}[STR]${RST} %s\n" "$*"; }
warn()    { printf "${YEL}[STR]!${RST} %s\n" "$*" >&2; }
die()     { printf "${RED}[STR]X${RST} %s\n" "$*" >&2; exit 1; }

print_banner() {
    local msg="$*"
    local bar=$(printf '%0.s=' $(seq 1 60))
    printf "${CYN}%s\n%s\n%s${RST}\n" "$bar" "  $msg" "$bar"
}

# ---- Path helpers -----------------------------------------------------------

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[1]:-$0}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPTS_DIR/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$ROOT_DIR/build}"
PREBUILT_DIR="${PREBUILT_DIR:-$ROOT_DIR/app/src/main/cpp/prebuilt}"

mkdir -p "$BUILD_DIR" "$PREBUILT_DIR"

# ---- NDK discovery ----------------------------------------------------------

find_ndk() {
    local tried=(
        "${ANDROID_NDK_HOME:-}"
        "${ANDROID_NDK_ROOT:-}"
        "$HOME/Android/Sdk/ndk/${NDK_VERSION:-26.1.10909125}"
        "/opt/android-sdk/ndk/${NDK_VERSION:-26.1.10909125}"
    )
    for p in "${tried[@]}"; do
        if [[ -n "$p" && -d "$p" ]]; then printf '%s' "$p"; return 0; fi
    done
    return 1
}

ndk_toolchain_bin() {
    local ndk="$(find_ndk)"
    local host="$(uname -s | tr '[:upper:]' '[:lower:]')"
    printf '%s/toolchains/llvm/prebuilt/%s-x86_64/bin' "$ndk" "$host"
}

# ---- Common cross-compile env vars ------------------------------------------

setup_android_env() {
    local ndk="$(find_ndk)" || die "NDK not found. Run setup_toolchain.sh first."
    export ANDROID_NDK_HOME="$ndk"
    local bin_dir="$(ndk_toolchain_bin)"
    export PATH="$bin_dir:$PATH"
    export CC="aarch64-linux-android26-clang"
    export CXX="aarch64-linux-android26-clang++"
    export AR="llvm-ar"
    export RANLIB="llvm-ranlib"
    export STRIP="llvm-strip"
    export PKG_CONFIG_PATH=""
    export CFLAGS="-O2 -fPIC -DNDEBUG"
    export CXXFLAGS="-O2 -fPIC -DNDEBUG -std=c++17"
    export LDFLAGS="-Wl,--gc-sections -Wl,--strip-all"
    log_ok "Cross-compile env: $CC"
}

# ---- Per-component destination layout ---------------------------------------

install_lib() {
    local src="$1"; shift
    local out="$PREBUILT_DIR/arm64-v8a/$1"
    [[ -f "$src" ]] || return 1
    mkdir -p "$(dirname "$out")"
    cp -f "$src" "$out"
    "${STRIP:-strip}" --strip-unneeded "$out" 2>/dev/null || true
    log "  installed: $out"
}
