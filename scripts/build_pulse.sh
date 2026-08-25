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
# Use the host's pkg-config — without this, meson fails with
# "Pkg-config for machine host machine not found" because it
# looks for an Android-targeted pkg-config binary that doesn't
# exist.  Host .pc files (x86_64 Linux) won't match Android
# anyway, but meson treats each dep lookup as "not found"
# rather than erroring out globally.
pkgconfig = '/usr/bin/pkg-config'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

# Stub libintl.h — Android's bionic libc has no gettext/libintl.
# PulseAudio's i18n.h unconditionally does #include <libintl.h>.
# Provide a header that inlines gettext/dgettext/etc. as identity
# functions (return input string unchanged) — translation isn't
# needed for our stub libpulse.so used by Wine's pulseaudio driver
# for IPC over a FIFO.
STUB_DIR="$PA_BUILD/stubs"
mkdir -p "$STUB_DIR"
cat > "$STUB_DIR/libintl.h" <<'EOF'
/* Stub libintl.h for Android cross-compile. */
#ifndef STRONGHOLDDROID_LIBINTL_STUB
#define STRONGHOLDDROID_LIBINTL_STUB
#ifdef __cplusplus
extern "C" {
#endif
static inline const char *gettext(const char *msgid) { return msgid; }
static inline const char *dgettext(const char *domainname, const char *msgid) { (void)domainname; return msgid; }
static inline const char *dcgettext(const char *domainname, const char *msgid, int category) { (void)domainname; (void)category; return msgid; }
static inline const char *ngettext(const char *msgid1, const char *msgid2, unsigned long n) { return (n == 1) ? msgid1 : msgid2; }
static inline const char *dngettext(const char *domainname, const char *msgid1, const char *msgid2, unsigned long n) { (void)domainname; return (n == 1) ? msgid1 : msgid2; }
static inline const char *dcngettext(const char *domainname, const char *msgid1, const char *msgid2, unsigned long n, int category) { (void)domainname; (void)category; return (n == 1) ? msgid1 : msgid2; }
static inline const char *bindtextdomain(const char *domainname, const char *dirname) { (void)dirname; return domainname; }
static inline const char *bind_textdomain_codeset(const char *domainname, const char *codeset) { (void)codeset; return domainname; }
static inline const char *textdomain(const char *domainname) { return domainname ? domainname : "messages"; }
#ifdef __cplusplus
}
#endif
#endif
EOF

log "Configuring PulseAudio (meson)..."
# PulseAudio v16.1 meson_options.txt — valid options we use:
#   -Ddaemon=false        — we don't ship a daemon, only the client lib
#   -Ddoxygen=false       — skip docs
#   -Dman=false           — skip man pages
#   -Dtests=false         — skip tests
#   -Ddatabase=simple     — in-memory database (no gdbm/tdb dependency;
#                           the daemon is disabled so this is unused
#                           anyway, but meson still wants the option set)
# Removed (caused "Unknown options" error in pipeline #10):
#   -Datomic-arm-linux-help=true   — typo; correct name is
#                                    -Datomic-arm-linux-helpers, and
#                                    it's only relevant for armv7 (32-bit)
#                                    where atomic intrinsics may need
#                                    helper functions.  arm64 has native
#                                    atomics so this option is unused.
#   -Dstream-restore=true          — not a real meson option; stream-restore
#                                    is a *module name*, not a build flag.
#   -Dnls=false                    — not a real meson option in
#                                    pulseaudio v16.1 (the meson_options.txt
#                                    has no 'nls' option).
#
# Patch 1: PulseAudio's meson.build line 377 does
#   libintl_dep = cc.find_library('intl')
# unconditionally when `dgettext` isn't in libc (which is the case for
# Android's bionic).  Patch it to `required: false` so the build doesn't
# fail when libintl isn't available — dgettext just becomes a no-op,
# English strings are returned as-is, which is fine for our stub lib.
#
# Patch 2: PulseAudio's meson.build line 605 does
#   sndfile_dep = dependency('sndfile', version : '>= 1.0.20')
# with required: true (default).  libsndfile isn't available for Android
# and we don't need it (only the daemon uses it for sample format
# handling).  Patch to required: false.
sed -i "s|cc\.find_library('intl')|cc.find_library('intl', required: false)|g" \
    "$PA_SRC/meson.build"
sed -i "s|dependency('sndfile', version : '>= 1.0.20')|dependency('sndfile', version : '>= 1.0.20', required: false)|g" \
    "$PA_SRC/meson.build"
# Patch 3: Remove 'execinfo.h' from meson's check_headers list.
# Cross-compile quirk: meson's cc.has_header('execinfo.h') passes because
# the HOST (Ubuntu 22.04) has /usr/include/execinfo.h.  The cross-compiler
# then 'finds' the header via its fallback include path and defines
# HAVE_EXECINFO_H.  But Android's bionic libc has no backtrace() /
# backtrace_symbols() (those are glibc-only), so the source compiles but
# fails to link / call the undeclared functions.
# Removing 'execinfo.h' from the list means HAVE_EXECINFO_H won't be
# defined, and pulsecore/log.c will skip the backtrace code block
# entirely (it's wrapped in #ifdef HAVE_EXECINFO_H).
sed -i "/^  'execinfo.h',$/d" "$PA_SRC/meson.build"
# Patch 4: Remove sndfile-util.c from libpulsecommon_sources list.
# Unlike x11 (which is gated by `if x11_dep.found()`), sndfile-util.c
# is included UNCONDITIONALLY in src/meson.build line 68.  Even with
# sndfile_dep=required:false, the source file gets compiled and tries
# to #include <sndfile.h> which isn't available for Android.
sed -i "/pulsecore\/sndfile-util\.c/d" "$PA_SRC/src/meson.build"
sed -i "/pulsecore\/sndfile-util\.h/d" "$PA_SRC/src/meson.build"

meson setup "$PA_SRC" . \
    --cross-file android-cross.txt \
    --default-library=shared \
    --buildtype=release \
    -Ddaemon=false \
    -Ddoxygen=false \
    -Dman=false \
    -Dtests=false \
    -Ddatabase=simple \
    -Dx11=disabled \
    -Dalsa=disabled \
    -Dasyncns=disabled \
    -Davahi=disabled \
    -Dbluez5=disabled \
    -Ddbus=disabled \
    -Delogind=disabled \
    -Dfftw=disabled \
    -Dglib=disabled \
    -Dgsettings=disabled \
    -Dgstreamer=disabled \
    -Dgtk=disabled \
    -Dhal-compat=false \
    -Djack=disabled \
    -Dlirc=disabled \
    -Dopenssl=disabled \
    -Dorc=disabled \
    -Doss-output=disabled \
    -Dsamplerate=disabled \
    -Dsoxr=disabled \
    -Dspeex=disabled \
    -Dsystemd=disabled \
    -Dtcpwrap=disabled \
    -Dudev=disabled \
    -Dvalgrind=disabled \
    -Dwebrtc-aec=disabled \
    -Dadrian-aec=false \
    -Dc_args="-I${STUB_DIR}" \
    -Dcpp_args="-I${STUB_DIR}"

log "Compiling PulseAudio..."
ninja -j"$(nproc)"

install_lib "$PA_BUILD/src/pulse/libpulse.so.0.*" "libpulse.so.0" || true
install_lib "$PA_BUILD/src/pulse/libpulse-simple.so.0.*" "libpulse-simple.so.0" || true
install_lib "$PA_BUILD/src/pulse/libpulsecommon-*.so" "libpulsecommon.so" || true

log_ok "PulseAudio stub built"
print_banner "PulseAudio build complete ✓"
