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

# ---- pkg-config isolation ---------------------------------------------------
# NEVER let the build see host .pc files.  On dev boxes with libsndfile1-dev
# installed, pkg-config resolves `sndfile` to the HOST's x86_64 lib and injects
# `-I/usr/include/opus -I/usr/include/x86_64-linux-gnu` into the cross-compile
# flags — host glibc headers + NDK bionic headers = fatal error
# ("function-like macro '__GNUC_PREREQ' is not defined" from sys/cdefs.h).
# The CI container is bare (no libsndfile-dev) so it worked there by accident.
# An empty PKG_CONFIG_LIBDIR makes pkg-config find NOTHING, which matches
# the CI environment exactly.
export PKG_CONFIG_LIBDIR=""
export PKG_CONFIG_PATH=""

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
# Patch 5: Remove the PTHREAD_PRIO_INHERIT header-symbol check.
# Same cross-compile quirk as execinfo.h: meson's
# cc.has_header_symbol('pthread.h', 'PTHREAD_PRIO_INHERIT') passes
# because the HOST's pthread.h defines it (glibc).  The cross-compiler
# finds it via the fallback include path, so HAVE_PTHREAD_PRIO_INHERIT
# gets defined.  But Android's bionic doesn't declare
# pthread_mutexattr_setprotocol() at all, so mutex-posix.c fails
# with "call to undeclared function 'pthread_mutexattr_setprotocol'".
# Removing the check means HAVE_PTHREAD_PRIO_INHERIT isn't defined,
# and mutex-posix.c skips the priority-inheritance code path
# (which is just an optimization for real-time threads).
sed -i "/cc.has_header_symbol('pthread.h', 'PTHREAD_PRIO_INHERIT')/,/^endif$/d" \
    "$PA_SRC/meson.build"
# Patch 6: Skip building CLI utilities (pacat, pactl, pasuspender, etc.).
# src/utils/meson.build unconditionally builds `pacat` and `pactl`
# executables when get_option('client') is true (and we need client=true
# for libpulse).  These utilities #include <sndfile.h> which isn't
# available for Android.  We don't need the utilities — only the
# libpulse.so client library (used by Wine's pulseaudio driver).
# Add subdir_done() at the top of src/utils/meson.build to skip it.
echo 'subdir_done()' > "$PA_SRC/src/utils/meson.build"
# Patch 7: Disable version-script linking.
# src/pulse/meson.build applies `-Wl,-version-script=.../src/pulse/map-file`
# to libpulse, libpulse-simple, and libpulsecommon.  The map-file lists
# ALL exported symbols across all PulseAudio libraries — including
# pa_glib_mainloop_* (defined in glib mainloop, which we disabled) and
# pa_simple_* (defined in libpulse-simple).  lld (NDK's linker) is
# strict: if a version-script symbol isn't defined in the linked
# objects, it errors out with "version script assignment of 'PULSE_0'
# to symbol 'X' failed: symbol not defined".
# Set versioning_link_args to an empty list to skip the version script.
sed -i "s|versioning_link_args = .*|versioning_link_args = []|" \
    "$PA_SRC/src/pulse/meson.build"

# Resume-safety: if meson already configured this build dir (e.g. a retried
# CI job or a local re-run after a timeout), skip `meson setup` — it refuses
# to reconfigure an existing builddir and ninja resumes incrementally.
if [[ -f "$PA_BUILD/build.ninja" ]]; then
    log "meson build dir already configured — resuming incremental build"
else
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
fi

log "Compiling PulseAudio..."
ninja -j"$(nproc)"

# Use meson install to properly install PulseAudio to a destdir,
# then merge into $PREBUILT_DIR/arm64-v8a.  This handles .so
# symlinking, header installs, and pkg-config file generation
# automatically (we don't have to fight with libpulse.so.0.X version
# suffix globs).
PULSE_DESTDIR="$PA_BUILD/destdir"
DESTDIR="$PULSE_DESTDIR" meson install --destdir="$PULSE_DESTDIR" 2>&1 | tail -10 || true

PULSE_LIB_DIR="$PREBUILT_DIR/arm64-v8a/lib"
mkdir -p "$PULSE_LIB_DIR" "$PREBUILT_DIR/arm64-v8a/include"

# Merge the installed tree from meson install into our prebuilt dir.
if [[ -d "$PULSE_DESTDIR/usr/local/lib" ]]; then
    cp -af "$PULSE_DESTDIR/usr/local/lib/"* "$PULSE_LIB_DIR/" 2>/dev/null || true
    cp -af "$PULSE_DESTDIR/usr/local/include/"* "$PREBUILT_DIR/arm64-v8a/include/" 2>/dev/null || true
elif [[ -d "$PULSE_DESTDIR/usr/lib" ]]; then
    cp -af "$PULSE_DESTDIR/usr/lib/"* "$PULSE_LIB_DIR/" 2>/dev/null || true
    cp -af "$PULSE_DESTDIR/usr/include/"* "$PREBUILT_DIR/arm64-v8a/include/" 2>/dev/null || true
else
    # Fallback: copy from build dir directly.
    log "WARN: meson install destdir not found, falling back to direct copy"
    cp -f "$PA_BUILD"/src/pulse/libpulse*.so* "$PULSE_LIB_DIR/" 2>/dev/null || true
    cp -f "$PA_BUILD"/src/pulse/libpulsecommon*.so* "$PULSE_LIB_DIR/" 2>/dev/null || true
    cp -f "$PA_SRC"/src/pulse/*.h "$PREBUILT_DIR/arm64-v8a/include/pulse/" 2>/dev/null || true
fi

# Ensure linker-friendly symlinks exist (libpulse.so → libpulse.so.0).
# meson install usually creates these, but double-check.
[[ -f "$PULSE_LIB_DIR/libpulse.so.0" && ! -L "$PULSE_LIB_DIR/libpulse.so" ]] && \
    ln -sf libpulse.so.0 "$PULSE_LIB_DIR/libpulse.so"
[[ -f "$PULSE_LIB_DIR/libpulse-simple.so.0" && ! -L "$PULSE_LIB_DIR/libpulse-simple.so" ]] && \
    ln -sf libpulse-simple.so.0 "$PULSE_LIB_DIR/libpulse-simple.so"

# Sanity-check what we ended up with (visible in CI logs).
log "=== PulseAudio installed files ==="
ls -la "$PULSE_LIB_DIR/" | head -20
log "=== pkgconfig ==="
ls -la "$PULSE_LIB_DIR/pkgconfig/" 2>/dev/null | head -5

# Ensure our pkg-config .pc file exists (meson install generates one
# too, but ours has the right paths).
mkdir -p "$PULSE_LIB_DIR/pkgconfig"
cat > "$PULSE_LIB_DIR/pkgconfig/libpulse.pc" <<EOF
prefix=$PREBUILT_DIR/arm64-v8a
exec_prefix=\${prefix}
libdir=\${exec_prefix}/lib
includedir=\${prefix}/include

Name: libpulse
Description: PulseAudio Client Development Library (StrongholdDroid stub)
Version: 16.1.0
Libs: -L\${libdir} -lpulse -lpulsecommon
Cflags: -I\${includedir}
EOF

log_ok "PulseAudio stub built (with headers, symlinks, + .pc)"

# ---- Convenience symlinks at $PREBUILT_DIR/arm64-v8a/ (PARENT of lib/) -----
# Downstream consumers (build_apk.sh EXPECTED_LIBS, .circleci/config.yml
# `test -f`, CMakeLists.txt IMPORTED_LOCATION) all expect libpulse.so at
# the TOP-LEVEL prebuilt dir (arm64-v8a/libpulse.so), NOT under
# arm64-v8a/lib/. Without these symlinks the APK build's sanity-check
# fails with "Missing prebuilt: libpulse.so" even though meson install
# succeeded.  Wine's configure still sees the real files under lib/
# via PKG_CONFIG_PATH/PULSE_LIBS=-L$WINE_OUT/lib.
PULSE_PARENT_DIR="$PREBUILT_DIR/arm64-v8a"
log "Installing convenience symlinks at $PULSE_PARENT_DIR/ ..."
for f in libpulse.so.0 libpulse.so libpulse-simple.so.0 libpulse-simple.so; do
    src_path="$PULSE_LIB_DIR/$f"
    dst_path="$PULSE_PARENT_DIR/$f"
    if [[ -e "$src_path" || -L "$src_path" ]] && [[ ! -e "$dst_path" ]]; then
        ln -sf "lib/$f" "$dst_path"
        log "  linked: $dst_path → lib/$f"
    fi
done
# libpulsecommon-*.so has a version-suffixed name that varies; glob it.
# NOTE: meson installs it into $libdir/pulseaudio/ (privlibdir), not $libdir.
# Normalize it to $PULSE_LIB_DIR (top level) first so Wine's -L$libdir link
# search and the parent-dir symlinks both resolve consistently.
if [[ -d "$PULSE_LIB_DIR/pulseaudio" ]]; then
    for f in "$PULSE_LIB_DIR/pulseaudio"/libpulsecommon-*.so; do
        [[ -e "$f" || -L "$f" ]] || continue
        base="$(basename "$f")"
        if [[ ! -e "$PULSE_LIB_DIR/$base" ]]; then
            cp -f "$f" "$PULSE_LIB_DIR/$base"
            log "  normalized: $PULSE_LIB_DIR/$base (from pulseaudio/ subdir)"
        fi
    done
fi
for f in "$PULSE_LIB_DIR"/libpulsecommon-*.so; do
    [[ -e "$f" || -L "$f" ]] || continue
    base="$(basename "$f")"
    if [[ ! -e "$PULSE_PARENT_DIR/$base" ]]; then
        ln -sf "lib/$base" "$PULSE_PARENT_DIR/$base"
        log "  linked: $PULSE_PARENT_DIR/$base → lib/$base"
    fi
done
# Sanity-check: the convenience symlink at parent dir resolves.
if [[ -L "$PULSE_PARENT_DIR/libpulse.so" && -f "$PULSE_PARENT_DIR/libpulse.so" ]]; then
    log_ok "libpulse.so reachable at $PULSE_PARENT_DIR/libpulse.so"
else
    warn "libpulse.so symlink not resolvable at parent — downstream APK build may fail"
fi

print_banner "PulseAudio build complete ✓"
