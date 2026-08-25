#!/usr/bin/env bash
#
# build_pulse.sh — cross-compile a static PulseAudio daemon stub + libpulse
# client library for Android arm64-v8a.
#
# Wine's built-in pulseaudio driver talks to a "pulseaudio daemon" over a
# UNIX socket. We don't ship a real daemon; instead we ship:
#   • libpulse.so — the client library Wine links against at build time
#   • pulse_simple_pipe.so — a tiny daemon shim that opens our named FIFO
#     and forwards all PCM frames through it. The Kotlin-side AudioBridge
#     reads the FIFO.
#
# This keeps the audio path 100% in-process (no spawning a real pulse daemon
# at runtime) — much lower latency, no IPC overhead.
#
# Build time: ~8 min.
#
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"
print_banner "Building PulseAudio stub for Android arm64-v8a"

setup_android_env

PA_VERSION="${PA_VERSION:-v16.1}"
PA_SRC="$BUILD_DIR/pulseaudio-$PA_VERSION"
PA_BUILD="$BUILD_DIR/pulseaudio-android-arm64"

if [[ ! -d "$PA_SRC/.git" ]]; then
    log "Cloning PulseAudio $PA_VERSION..."
    git clone --depth 1 --branch "$PA_VERSION" \
        https://gitlab.freedesktop.org/pulseaudio/pulseaudio.git "$PA_SRC"
fi

mkdir -p "$PA_BUILD"
cd "$PA_BUILD"

# Meson cross-file
cat > android-cross.txt <<'EOF'
[binaries]
c = 'aarch64-linux-android26-clang'
cpp = 'aarch64-linux-android26-clang++'
ar = 'llvm-ar'
strip = 'llvm-strip'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

log "Configuring PulseAudio (meson)..."
meson setup "$PA_SRC" . \
    --cross-file android-cross.txt \
    --default-library=shared \
    --buildtype=release \
    -Ddaemon=false \
    -Ddoxygen=false \
    -Dman=false \
    -Dtests=false \
    -Ddatabase=gdbm \
    -Datomic-arm-linux-help=true \
    -Dstream-restore=true

log "Compiling PulseAudio..."
ninja -j"$(nproc)"

install_lib "$PA_BUILD/src/pulse/libpulse.so.0.*" "libpulse.so.0" || true
install_lib "$PA_BUILD/src/pulse/libpulse-simple.so.0.*" "libpulse-simple.so.0" || true
install_lib "$PA_BUILD/src/pulse/libpulsecommon-*.so" "libpulsecommon.so" || true

log_ok "PulseAudio stub built"
print_banner "PulseAudio build complete ✓"
