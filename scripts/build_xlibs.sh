#!/usr/bin/env bash
#
# build_xlibs.sh — cross-compile the X11 client libraries for Android arm64.
#
# WHY THIS EXISTS
# ---------------
# Wine was previously built with `--without-x`, which produced a runtime with
# NO display driver at all: winex11.drv was never compiled, so Wine could not
# open a single window — every game (and even `wineboot`) died silently.
# StrongholdDroid now uses the classic "Wine + X11" display path:
#
#   game → user32/gdi32 → win32u → winex11.drv → libX11 → TCP :6000
#          → XServer XSDL / Termux:X11 app (user-installable) → screen
#
# The app pre-checks 127.0.0.1:6000 before launching Wine
# (see EnvironmentBuilder.isXServerReachable).
#
# WHAT IS BUILT (release tarballs ship pre-generated configure scripts —
# no autoreconf needed):
#   xorgproto        protocol headers (no compiled code)
#   pthread-stubs    empty pthread stubs (libxcb configure wants it)
#   libxau, libxdmcp X authentication helpers
#   xcb-proto        python protocol descriptions (build-time only)
#   libxcb           X protocol C binding
#   xtrans           transport layer headers for libX11
#   libx11           THE library winex11.drv links against
#   libxext, libxrender, libxfixes, libxcursor, libxrandr,
#   libxinerama, libxcomposite, libxi   (optional X extensions wine probes)
#
# Output:
#   prebuilt/arm64-v8a/xlibs/include/   headers (wine build-time only)
#   prebuilt/arm64-v8a/xlibs/lib/       .so + .pc (wine build-time)
#   build_apk.sh stages the .so files into assets/usr/lib for the APK.
#
# Build time: ~12 min on 4 cores (each lib is tiny).
#
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"
print_banner "Building X11 client libraries for Android arm64-v8a"

setup_android_env

XLIBS_OUT="$PREBUILT_DIR/arm64-v8a/xlibs"
XLIBS_SRC="$BUILD_DIR/xlibs-src"
mkdir -p "$XLIBS_OUT" "$XLIBS_SRC"

# ---- Tarball versions (all verified against xorg.freedesktop.org) ----------
XORGPROTO_VER=2024.1
UTIL_MACROS_VER=1.20.1
XAU_VER=1.0.11
XDMCP_VER=1.1.5
XCB_PROTO_VER=1.17.0
XCB_VER=1.17.0
XTRANS_VER=1.5.2
X11_VER=1.8.10
XEXT_VER=1.3.6
XRENDER_VER=0.9.11
XFIXES_VER=6.0.1
XCURSOR_VER=1.2.2
XRANDR_VER=1.5.4
XINERAMA_VER=1.1.5
XCOMPOSITE_VER=0.4.6
XXF86VM_VER=1.1.5
XI_VER=1.8.2

# ---- Fetch helper with mirror fallback --------------------------------------
fetch() {  # fetch <url-primary> <url-fallback> <dest-tarball>
    local primary="$1" fallback="$2" dest="$3"
    if [[ ! -s "$dest" ]]; then
        log "  downloading $(basename "$dest") ..."
        curl -fsSL --retry 3 -o "$dest" "$primary" \
            || curl -fsSL --retry 3 -o "$dest" "$fallback" \
            || die "failed to download $(basename "$dest")"
    fi
}

# xorg.freedesktop.org is the canonical host; the gitlab mirror is the
# fallback (the main server has had intermittent outages).
XORG_BASE="https://xorg.freedesktop.org/releases/individual"
XORG_MIRROR="https://gitlab.freedesktop.org/xorg"

cd "$XLIBS_SRC"

fetch "$XORG_BASE/proto/xorgproto-$XORGPROTO_VER.tar.xz" \
      "$XORG_MIRROR/proto/xorgproto/-/archive/xorgproto-$XORGPROTO_VER/xorgproto-$XORGPROTO_VER.tar.gz" \
      "xorgproto-$XORGPROTO_VER.tar.xz"
fetch "$XORG_BASE/util/util-macros-$UTIL_MACROS_VER.tar.xz" \
      "$XORG_MIRROR/util/util-macros/-/archive/util-macros-$UTIL_MACROS_VER/util-macros-$UTIL_MACROS_VER.tar.gz" \
      "util-macros-$UTIL_MACROS_VER.tar.xz"
fetch "$XORG_BASE/lib/libXau-$XAU_VER.tar.xz" \
      "$XORG_MIRROR/lib/libXau/-/archive/libXau-$XAU_VER/libXau-$XAU_VER.tar.gz" \
      "libXau-$XAU_VER.tar.xz"
fetch "$XORG_BASE/lib/libXdmcp-$XDMCP_VER.tar.xz" \
      "$XORG_MIRROR/lib/libXdmcp/-/archive/libXdmcp-$XDMCP_VER/libXdmcp-$XDMCP_VER.tar.gz" \
      "libXdmcp-$XDMCP_VER.tar.xz"
fetch "$XORG_BASE/xcb/xcb-proto-$XCB_PROTO_VER.tar.xz" \
      "$XORG_MIRROR/xorg/proto/xcb-proto/-/archive/xcb-proto-$XCB_PROTO_VER/xcb-proto-$XCB_PROTO_VER.tar.gz" \
      "xcb-proto-$XCB_PROTO_VER.tar.xz"
fetch "$XORG_BASE/xcb/libxcb-$XCB_VER.tar.xz" \
      "$XORG_MIRROR/xorg/lib/libxcb/-/archive/libxcb-$XCB_VER/libxcb-$XCB_VER.tar.gz" \
      "libxcb-$XCB_VER.tar.xz"
fetch "$XORG_BASE/lib/xtrans-$XTRANS_VER.tar.xz" \
      "$XORG_MIRROR/lib/xtrans/-/archive/xtrans-$XTRANS_VER/xtrans-$XTRANS_VER.tar.gz" \
      "xtrans-$XTRANS_VER.tar.xz"
fetch "$XORG_BASE/lib/libX11-$X11_VER.tar.xz" \
      "$XORG_MIRROR/lib/libX11/-/archive/libX11-$X11_VER/libX11-$X11_VER.tar.gz" \
      "libX11-$X11_VER.tar.xz"
fetch "$XORG_BASE/lib/libXext-$XEXT_VER.tar.xz" \
      "$XORG_MIRROR/lib/libXext/-/archive/libXext-$XEXT_VER/libXext-$XEXT_VER.tar.gz" \
      "libXext-$XEXT_VER.tar.xz"
fetch "$XORG_BASE/lib/libXrender-$XRENDER_VER.tar.xz" \
      "$XORG_MIRROR/lib/libXrender/-/archive/libXrender-$XRENDER_VER/libXrender-$XRENDER_VER.tar.gz" \
      "libXrender-$XRENDER_VER.tar.xz"
fetch "$XORG_BASE/lib/libXfixes-$XFIXES_VER.tar.xz" \
      "$XORG_MIRROR/lib/libXfixes/-/archive/libXfixes-$XFIXES_VER/libXfixes-$XFIXES_VER.tar.gz" \
      "libXfixes-$XFIXES_VER.tar.xz"
fetch "$XORG_BASE/lib/libXcursor-$XCURSOR_VER.tar.xz" \
      "$XORG_MIRROR/lib/libXcursor/-/archive/libXcursor-$XCURSOR_VER/libXcursor-$XCURSOR_VER.tar.gz" \
      "libXcursor-$XCURSOR_VER.tar.xz"
fetch "$XORG_BASE/lib/libXrandr-$XRANDR_VER.tar.xz" \
      "$XORG_MIRROR/lib/libXrandr/-/archive/libXrandr-$XRANDR_VER/libXrandr-$XRANDR_VER.tar.gz" \
      "libXrandr-$XRANDR_VER.tar.xz"
fetch "$XORG_BASE/lib/libXinerama-$XINERAMA_VER.tar.xz" \
      "$XORG_MIRROR/lib/libXinerama/-/archive/libXinerama-$XINERAMA_VER/libXinerama-$XINERAMA_VER.tar.gz" \
      "libXinerama-$XINERAMA_VER.tar.xz"
fetch "$XORG_BASE/lib/libXcomposite-$XCOMPOSITE_VER.tar.xz" \
      "$XORG_MIRROR/lib/libXcomposite/-/archive/libXcomposite-$XCOMPOSITE_VER/libXcomposite-$XCOMPOSITE_VER.tar.gz" \
      "libXcomposite-$XCOMPOSITE_VER.tar.xz"
# libXxf86vm — wine uses it for ddraw/d3d FULLSCREEN resolution switching
# (Stronghold Crusader runs fullscreen via DirectDraw SetDisplayMode).
fetch "$XORG_BASE/lib/libXxf86vm-$XXF86VM_VER.tar.xz" \
      "$XORG_MIRROR/lib/libXxf86vm/-/archive/libXxf86vm-$XXF86VM_VER/libXxf86vm-$XXF86VM_VER.tar.gz" \
      "libXxf86vm-$XXF86VM_VER.tar.xz"
fetch "$XORG_BASE/lib/libXi-$XI_VER.tar.xz" \
      "$XORG_MIRROR/lib/libXi/-/archive/libXi-$XI_VER/libXi-$XI_VER.tar.gz" \
      "libXi-$XI_VER.tar.xz"

# ---- pthread-stubs: synthesized, not downloaded -----------------------------
# libxcb's configure runs PKG_CHECK_MODULES(pthread-stubs).  The real
# pthread-stubs package only matters on platforms WITHOUT pthreads in libc;
# Android's bionic has real pthreads, so an empty .pc is exactly equivalent
# (this is what distros with glibc do under the hood).  The upstream
# tarball URLs are unstable — synthesizing avoids a 404 build breaker.
mkdir -p "$XLIBS_OUT/lib/pkgconfig"
cat > "$XLIBS_OUT/lib/pkgconfig/pthread-stubs.pc" <<PCEOF
prefix=$XLIBS_OUT

Name: pthread-stubs
Description: pthread stubs (synthesized for Android/bionic — real pthreads in libc)
Version: 0.5
Libs:
Cflags:
PCEOF
log "  synthesized pthread-stubs.pc (bionic has real pthreads in libc)"

# Bionic merged pthreads into libc — there is no libpthread.a/so in the NDK
# sysroot, but xorg configure scripts add -lpthread anyway.  An empty static
# archive satisfies the linker; the real symbols come from libc (always
# linked).  Same trick Termux uses for xorg packages.
"${AR:-llvm-ar}" rcs "$XLIBS_OUT/lib/libpthread.a"
log "  synthesized empty libpthread.a (pthreads live in bionic libc)"

# ---- Cross-compile helper ---------------------------------------------------
# Every xorg lib uses the same idiom; the cross-compile quirks are:
#   * malloc(0)/realloc(0) runtime tests can't run when cross-compiling →
#     answer them via cache variables (equivalent of passing
#     --disable-malloc0returnsnull).
#   * --disable-shared + --enable-static would give smaller builds but wine
#     needs the .so at RUNTIME (LD_LIBRARY_PATH), so build both and stage
#     the .so files.  Actually: --disable-static to halve the build time;
#     we only ever consume the .so.
build_pkg() {  # build_pkg <tarball> <dirname> [extra configure args...]
    local tarball="$1"; shift
    local dir="$1"; shift
    if [[ -f "$XLIBS_OUT/.built-$dir" ]]; then
        log "  $dir already built — skipping"
        return 0
    fi
    log "  building $dir ..."
    rm -rf "$dir"
    case "$tarball" in
        *.tar.xz) tar -xJf "$tarball" ;;
        *.tar.gz) tar -xzf "$tarball" ;;
    esac
    local srcdir="$dir"
    [[ -d "$srcdir" ]] || srcdir="$(ls -d -- */ | head -1)"
    (
        cd "$srcdir"
        # shellcheck disable=SC2086
        ./configure \
            --host=aarch64-linux-android \
            --prefix="$XLIBS_OUT" \
            --disable-static \
            --enable-shared \
            --disable-malloc0returnsnull \
            ac_cv_func_malloc_0_nonnull=yes \
            ac_cv_func_realloc_0_nonnull=yes \
            CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
            CFLAGS="-O2 -fPIC ${CFLAGS_EXTRA:-}" \
            CPPFLAGS="-I$XLIBS_OUT/include" \
            LDFLAGS="-L$XLIBS_OUT/lib" \
            PKG_CONFIG_PATH="$XLIBS_OUT/lib/pkgconfig:$XLIBS_OUT/share/pkgconfig" \
            PKG_CONFIG_LIBDIR="$XLIBS_OUT/lib/pkgconfig:$XLIBS_OUT/share/pkgconfig" \
            "$@"
        make -j"$(nproc)" > "$XLIBS_SRC/last-build.log" 2>&1 || {
            tail -40 "$XLIBS_SRC/last-build.log" >&2; die "make failed for $dir"; }
        make install >> "$XLIBS_SRC/last-build.log" 2>&1 || die "make install failed for $dir"
    )
    touch "$XLIBS_OUT/.built-$dir"
    log_ok "  $dir ✓"
}

# ---- 1. Headers + macros packages -------------------------------------------
build_pkg "xorgproto-$XORGPROTO_VER.tar.xz" "xorgproto-$XORGPROTO_VER"
# util-macros ships the xorg-macros.pc that every later lib's configure
# looks up via pkg-config (plus the m4 macros in case anything autoreconfs).
build_pkg "util-macros-$UTIL_MACROS_VER.tar.xz" "util-macros-$UTIL_MACROS_VER"

# ---- 2. libxau + libxdmcp ---------------------------------------------------
build_pkg "libXau-$XAU_VER.tar.xz" "libXau-$XAU_VER"
build_pkg "libXdmcp-$XDMCP_VER.tar.xz" "libXdmcp-$XDMCP_VER"

# ---- 3. xcb-proto (python descriptions — build-time only) -------------------
build_pkg "xcb-proto-$XCB_PROTO_VER.tar.xz" "xcb-proto-$XCB_PROTO_VER"

# ---- 4. libxcb ---------------------------------------------------------------
# xcb-proto installs python modules into $XLIBS_OUT/lib/python3*/site-packages;
# libxcb's configure invokes python to generate C from them.
PY_SITE="$(ls -d "$XLIBS_OUT"/lib/python3*/site-packages 2>/dev/null | head -1 || true)"
if [[ -n "$PY_SITE" ]]; then
    export PYTHONPATH="$PY_SITE${PYTHONPATH:+:$PYTHONPATH}"
    log "  PYTHONPATH=$PY_SITE"
fi
build_pkg "libxcb-$XCB_VER.tar.xz" "libxcb-$XCB_VER" \
    --disable-xvmc --enable-xinput

# ---- 5. xtrans (headers for libX11 transport) --------------------------------
build_pkg "xtrans-$XTRANS_VER.tar.xz" "xtrans-$XTRANS_VER"

# ---- 6. libX11 ----------------------------------------------------------------
build_pkg "libX11-$X11_VER.tar.xz" "libX11-$X11_VER" \
    --disable-loadable-xcursor

# ---- 7. X extension libraries -------------------------------------------------
build_pkg "libXext-$XEXT_VER.tar.xz" "libXext-$XEXT_VER"
build_pkg "libXrender-$XRENDER_VER.tar.xz" "libXrender-$XRENDER_VER"
build_pkg "libXfixes-$XFIXES_VER.tar.xz" "libXfixes-$XFIXES_VER"
build_pkg "libXcursor-$XCURSOR_VER.tar.xz" "libXcursor-$XCURSOR_VER"
build_pkg "libXrandr-$XRANDR_VER.tar.xz" "libXrandr-$XRANDR_VER"
build_pkg "libXinerama-$XINERAMA_VER.tar.xz" "libXinerama-$XINERAMA_VER"
build_pkg "libXcomposite-$XCOMPOSITE_VER.tar.xz" "libXcomposite-$XCOMPOSITE_VER"
build_pkg "libXxf86vm-$XXF86VM_VER.tar.xz" "libXxf86vm-$XXF86VM_VER"
build_pkg "libXi-$XI_VER.tar.xz" "libXi-$XI_VER"

# ---- Sanity check -------------------------------------------------------------
# libtool builds UNVERSIONED .so files for the android host (no libX11.so.6 —
# just libX11.so).  This is actually ideal for Android: the dynamic linker
# matches NEEDED entries by exact filename, and wine's winex11.drv will record
# NEEDED libX11.so (the SONAME below), which is exactly what we ship.
for lib in libX11.so libxcb.so libXau.so libXdmcp.so libXext.so \
           libXrender.so libXfixes.so libXcursor.so libXrandr.so \
           libXinerama.so libXcomposite.so libXxf86vm.so libXi.so; do
    [[ -e "$XLIBS_OUT/lib/$lib" ]] || die "X11 build incomplete: $lib missing"
done

# Verify they really are arm64 ELF (not accidentally host binaries).
# Use readelf (from binutils/build-essential) instead of `file` which is not
# installed in the build container.
for f in "$XLIBS_OUT"/lib/*.so; do
    readelf -h "$f" 2>/dev/null | grep -q "Machine:.*AArch64" || die "$f is not an arm64 ELF!"
done

log "  X11 libs total size: $(du -sh "$XLIBS_OUT/lib" | cut -f1)"
log_ok "All X11 client libraries built"
print_banner "X11 libraries build complete ✓"
