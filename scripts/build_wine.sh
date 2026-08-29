#!/usr/bin/env bash
#
# build_wine.sh — cross-compile Wine 9.0 for Android arm64-v8a.
#
# StrongholdDroid uses Wine for the Windows API layer (kernel32, user32,
# ddraw, dsound, dinput8, ...).
#
# Architecture ("Arm64 Wine WOW64", see box64 docs/WINE.md):
#   1. The unix side (ntdll, wineserver, wine loader) is compiled as
#      ARM64 ELF against Android's bionic libc — it runs NATIVELY on the
#      device, no instruction translation for wine itself.
#   2. Wine's builtin PE DLLs are compiled for TWO architectures:
#        - aarch64-windows  (the native 64-bit layer: ntdll, kernel32, ...)
#        - i386-windows     (the WoW64 32-bit layer for 32-bit games —
#                            Stronghold Crusader is a 32-bit x86 app)
#      via --enable-archs=aarch64,i386.  The 32-bit x86 code is executed by
#      box64's wow64 cpu dll (wowbox64.dll, staged as xtajit.dll at
#      runtime), built by build_box64.sh — NOT by running wine under the
#      box64 executable.
#   3. DISPLAY: winex11.drv (built via --with-x + build_xlibs.sh) renders
#      into an X server the user runs on the device (XServer XSDL or
#      Termux:X11, reachable at 127.0.0.1:6000).  DirectDraw uses the GDI
#      renderer (set in EnvironmentBuilder registry tweaks) so no GL path
#      is required for Stronghold Crusader.
#      Wine links against the stub libpulse.so we ship (build_pulse.sh);
#      the AudioBridge on the Kotlin side owns the sink end of the FIFO
#      and forwards to AAudio.
#
# Android/bionic patches live in scripts/patches/wine/*.patch and are
# applied by apply_patches() below (posix_spawn is absent from bionic
# before API 28, etc.).
#
# Total build time on a 16-core box: ~35 min. Disk space: ~1.2 GB.
#
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"
print_banner "Building Wine 9.0 for Android arm64-v8a (WOW64)"

setup_android_env

WINE_VERSION="${WINE_VERSION:-9.0}"
WINE_SRC="${WINE_SRC:-$BUILD_DIR/wine-$WINE_VERSION}"
WINE_BUILD="$BUILD_DIR/wine-android-arm64"
WINE_OUT="$PREBUILT_DIR/arm64-v8a"

mkdir -p "$WINE_OUT" "$WINE_BUILD"

# ---- Fetch source -----------------------------------------------------------
if [[ ! -d "$WINE_SRC/.git" ]]; then
    log "Cloning Wine $WINE_VERSION..."
    # Primary: official GitLab. Fallback: GitHub mirror (gitlab.winehq.org
    # intermittently fails with "could not determine hash algorithm" from
    # some networks / CI runners).
    git clone --depth 1 --branch "wine-$WINE_VERSION" \
        https://gitlab.winehq.org/wine/wine.git "$WINE_SRC" \
    || git clone --depth 1 --branch "wine-$WINE_VERSION" \
        https://github.com/wine-mirror/wine.git "$WINE_SRC" \
    || die "failed to clone Wine $WINE_VERSION (gitlab + github mirror)"
fi

# ---- Android-specific patches ----------------------------------------------
apply_patches() {
    local pdir="$ROOT_DIR/scripts/patches/wine"
    if [[ -d "$pdir" ]]; then
        for p in "$pdir"/*.patch; do
            [[ -e "$p" ]] || continue
            if ( cd "$WINE_SRC" && git apply --check "$p" ) 2>/dev/null; then
                log "  applying patch: $(basename "$p")"
                ( cd "$WINE_SRC" && git apply --whitespace=fix "$p" ) \
                    || die "patch failed to apply: $p"
            elif ( cd "$WINE_SRC" && git apply --reverse --check "$p" ) 2>/dev/null; then
                log "  patch already applied: $(basename "$p") (skipping)"
            else
                die "patch does not apply cleanly: $p (source tree drifted — nuke \$BUILD_DIR/wine-$WINE_VERSION and retry)"
            fi
        done
    fi
}
apply_patches

# ---- Configure -------------------------------------------------------------
# We disable everything we don't need for SC:
#   • --with-x — CRITICAL: builds winex11.drv (the display driver).  Without
#     it wine cannot open ANY window and every GUI app (and wineboot)
#     dies silently.  The X11 client libs come from build_xlibs.sh.
#   • No Wayland / GTK — XSDL is the display target
#   • No CUPS / SANE / libcapi20 — printing/scanning not needed
#   • ddraw uses the GDI renderer (see EnvironmentBuilder) so desktop GL
#     is not required — keep --without-opengl to minimize build risk
#
# IMPORTANT: Wine's configure REQUIRES --with-wine-tools=DIR when
# cross-compiling.  The "wine tools" (winebuild, wrc, wmc, widl, etc.)
# must be BUILT NATIVELY for the build host (x86_64 Linux) and then
# used during cross-compilation.  Without this, configure fails with:
#   configure: error: you must use the --with-wine-tools option when
#   cross-compiling.
#
# Step A: build the wine TOOLS natively for the build host.
WINE_NATIVE_BUILD="$BUILD_DIR/wine-native-x86_64"
if [[ ! -x "$WINE_NATIVE_BUILD/tools/winebuild/winebuild" ]]; then
    log "Building native Wine tools (host x86_64)..."
    mkdir -p "$WINE_NATIVE_BUILD"
    cd "$WINE_NATIVE_BUILD"
    # Use CC=gcc explicitly — setup_toolchain.sh prepended the NDK's
    # aarch64-linux-android26-clang to PATH, which would intercept
    # configure's `cc` lookup and produce Android binaries (that
    # fail to run on the Linux x86_64 container).
    # NOTE: --without-pulse here — the native build only produces the
    # wine tools (winebuild/wrc/wmc/widl/winegcc).  Requiring host
    # libpulse-dev for it is pointless and fails on hosts without
    # libpulse-dev (the ANDROID libpulse stub is only used by the
    # cross-compile in Step B).
    CC=gcc CXX=g++ \
    "$WINE_SRC/configure" \
        --build="x86_64-pc-linux-gnu" \
        --host="x86_64-pc-linux-gnu" \
        --prefix="$WINE_NATIVE_BUILD/install" \
        --enable-win64 \
        --disable-win16 \
        --without-x \
        --without-wayland \
        --without-opengl \
        --without-vulkan \
        --without-cups \
        --without-sane \
        --without-capi \
        --without-gphoto \
        --without-usb \
        --without-pulse \
        --without-alsa \
        --without-oss \
        --without-coreaudio \
        --without-mingw
    # Build ONLY the tools needed by the cross-compile (winebuild, wrc, wmc,
    # widl, winegcc) plus the helper tools the cross Makefile references
    # (make_xftmpl, sfnt2fon, wineapploader, makedep — the latter two are
    # produced by configure itself).  A full top-level `make` would compile
    # ALL host-side DLLs too (~10 extra minutes of CI time per cold build)
    # for no benefit: only the tools are consumed via --with-wine-tools.
    make -j"$(nproc)" \
        tools/make_xftmpl \
        tools/winebuild/winebuild \
        tools/wrc/wrc \
        tools/wmc/wmc \
        tools/widl/widl \
        tools/winegcc/winegcc \
        tools/sfnt2fon/sfnt2fon \
        tools/wineapploader

    # wrc/wmc load NLS files (locale.nls, c_*.nls) relative to their own
    # location: <native-build>/nls/.  The build dir normally gets these as
    # symlinks via `make nls/...` targets which we don't invoke (we only
    # build the tools) — without them the cross-compile dies with
    #   "Error: unable to load locale.nls"  (tools/wrc/wrc.c: nlsdirs[0])
    for f in "$WINE_SRC"/nls/*.nls; do
        base="$(basename "$f")"
        [[ -e "$WINE_NATIVE_BUILD/nls/$base" ]] || \
            ln -sf "$f" "$WINE_NATIVE_BUILD/nls/$base"
    done
fi

# Step B: cross-configure for Android using the native tools.
log "Configuring Wine (cross-compile for Android arm64-v8a, WOW64)..."
cd "$WINE_BUILD"

# Resume-safety: if configure already ran in this build dir (retried CI job
# or a local re-run after a timeout), skip straight to `make` — make itself
# resumes incrementally.
if [[ -f "$WINE_BUILD/Makefile" ]]; then
    log "  build dir already configured — resuming incremental make"
else

# PE cross-compilers: wine's configure looks for aarch64-w64-mingw32-clang
# and i686-w64-mingw32-clang on PATH (provided by llvm-mingw — see
# common.sh setup_android_env which prepends it, and Dockerfile.build which
# installs it at /opt/llvm-mingw).  Without them configure aborts with
# "PE cross-compilation is required for ARM64, please install
#  clang/llvm-dlltool/lld, or llvm-mingw."  Wine 9.0 needs NO external
# `dlltool` for its own PE build (winebuild emits import libraries itself).
#
# X11 paths point at the xlibs sysroot built by build_xlibs.sh (which MUST
# run before this script — see build_all.sh step 2.5).  X_CFLAGS/X_LIBS are
# consumed by wine's X checks; CPPFLAGS/LDFLAGS make them visible to every
# other check as well.
XLIBS_SYSROOT="$PREBUILT_DIR/arm64-v8a/xlibs"
if [[ ! -e "$XLIBS_SYSROOT/lib/libX11.so" ]]; then
    die "X11 libs not found at $XLIBS_SYSROOT — run scripts/build_xlibs.sh first (see build_all.sh)."
fi

"$WINE_SRC/configure" \
    --host="aarch64-linux-android" \
    --build="x86_64-pc-linux-gnu" \
    --prefix="$WINE_OUT/usr" \
    --enable-archs="aarch64,i386" \
    --with-wine-tools="$WINE_NATIVE_BUILD" \
    --disable-wineandroid.drv \
    --with-x \
    --without-wayland \
    --without-opengl \
    --without-vulkan \
    --without-cups \
    --without-sane \
    --without-capi \
    --without-gphoto \
    --without-usb \
    --with-pulse \
    --without-alsa \
    --without-oss \
    --without-coreaudio \
    --without-freetype \
    --without-fontconfig \
    X_CFLAGS="-I$XLIBS_SYSROOT/include" \
    X_LIBS="-L$XLIBS_SYSROOT/lib" \
    PKG_CONFIG_PATH="$XLIBS_SYSROOT/lib/pkgconfig:$XLIBS_SYSROOT/share/pkgconfig:$WINE_OUT/lib/pkgconfig" \
    PKG_CONFIG_LIBDIR="$XLIBS_SYSROOT/lib/pkgconfig:$XLIBS_SYSROOT/share/pkgconfig:$WINE_OUT/lib/pkgconfig" \
    PULSE_CFLAGS="-I$WINE_OUT/include" \
    PULSE_LIBS="-L$WINE_OUT/lib -lpulse" \
    CPPFLAGS="-I$XLIBS_SYSROOT/include -I$WINE_OUT/include" \
    LDFLAGS="-L$XLIBS_SYSROOT/lib -L$WINE_OUT/lib -static-libstdc++"

fi   # configure-if-needed

# NOTE (history): the previous configuration used --enable-win64, which
# builds Wine's PE layer for x86_64-Windows ONLY — Stronghold Crusader is
# a 32-bit x86 game and would fail to load ("not a valid Win32
# application").  --enable-archs=aarch64,i386 enables wine's WoW64 mode:
# the unix side and the native PE layer are aarch64, and a full
# i386-windows PE layer is built for 32-bit processes, executed through
# the box64 wow64 cpu dll (xtajit.dll).
#
# --without-freetype / --without-fontconfig: cross-compile for Android
# can't see the host's freetype/fontconfig dev files (and Android
# doesn't ship them either).  Wine's own bitmap fonts (fonts/*.fon built
# by sfnt2fon from the bundled .ttf sources) are installed to
# $WINE_OUT/usr/share/wine/fonts and staged into the APK by build_apk.sh —
# game dialogs and wine message boxes use them.
#
# --with-x was validated locally (NDK r26b + llvm-mingw 20240619): wine's
# configure detects all 10 X libs with unversioned SONAMEs (libX11.so,
# libXext.so, ...) matching exactly what build_xlibs.sh ships, and
# winex11.drv compiles for both the aarch64-unix and PE layers.
#
# PKG_CONFIG_LIBDIR + PULSE_CFLAGS + PULSE_LIBS: Wine's
# WINE_PACKAGE_FLAGS(PULSE, ...) macro calls pkg-config to get the
# CFLAGS/LIBS for libpulse, then runs AC_CHECK_LIB with them.
# Setting PULSE_CFLAGS/PULSE_LIBS explicitly bypasses pkg-config
# entirely and gives Wine the values directly (an empty PULSE_LIBS
# would abort configure with "libpulse development files not found").

log "Compiling Wine (this takes ~25 min on a 16-core box)..."
make -j"$(nproc)"

log "Installing to $WINE_OUT..."
make install

# ---- Stage the binaries the app expects ------------------------------------
log "Staging artifacts..."
# On android hosts wine installs into $prefix/arm64-v8a/{bin,lib} (multiarch
# convention from Wine's android support) and the loader binary is named
# `wine` (wine64 is the x86_64-host name — see configure.ac:
# x86_64,*) wine_binary="wine64").
WINE_INSTALL="$WINE_OUT/usr/arm64-v8a"
install_lib "$WINE_INSTALL/bin/wine"            "libwine/wine"
install_lib "$WINE_INSTALL/bin/wineserver"      "libwine/wineserver"
# NOTE: no libwine.so is produced for android hosts — the wine loader is a
# small PIE executable (NEEDED: libc.so, libdl.so only) that dlopens
# lib/wine/aarch64-unix/ntdll.so at runtime.  The JNI library therefore
# does NOT link against libwine.

# The DLLs that ship inside the WINEPREFIX are kept separate so
# EnvironmentBuilder.ensureWinePrefix() can stage them at runtime.
# - aarch64-unix:     the ELF unix-side libs wine dlopens (ntdll.so, ...)
# - aarch64-windows:  the native 64-bit builtin PE DLLs (-> system32)
# - i386-windows:     the WoW64 32-bit builtin PE DLLs (-> syswow64) —
#                     what a 32-bit game like Stronghold Crusader loads
mkdir -p "$WINE_OUT/wine_dlls/aarch64-windows" \
         "$WINE_OUT/wine_dlls/i386-windows" \
         "$WINE_OUT/wine_dlls/aarch64-unix"
cp -f "$WINE_INSTALL/lib/wine/aarch64-windows/"* \
      "$WINE_OUT/wine_dlls/aarch64-windows/" 2>/dev/null || true
cp -f "$WINE_INSTALL/lib/wine/i386-windows/"* \
      "$WINE_OUT/wine_dlls/i386-windows/" 2>/dev/null || true
cp -f "$WINE_INSTALL/lib/wine/aarch64-unix/"*.so \
      "$WINE_OUT/wine_dlls/aarch64-unix/" 2>/dev/null || true

# Strip debug info from the staged PE files — unstripped they carry DWARF
# from `-g` and blow up to ~845 MB; llvm-strip understands COFF and brings
# the whole tree down to ~150 MB (a ~5.5x reduction).  PE exports live in
# the export directory, not the symbol table, so stripping is safe.
log "Stripping PE debug info..."
for f in "$WINE_OUT/wine_dlls/aarch64-windows/"*.dll \
         "$WINE_OUT/wine_dlls/aarch64-windows/"*.exe \
         "$WINE_OUT/wine_dlls/i386-windows/"*.dll \
         "$WINE_OUT/wine_dlls/i386-windows/"*.exe; do
    [[ -f "$f" ]] && "${STRIP:-llvm-strip}" --strip-debug "$f" 2>/dev/null || true
done
for f in "$WINE_OUT/wine_dlls/aarch64-unix/"*.so; do
    [[ -f "$f" ]] && "${STRIP:-llvm-strip}" --strip-unneeded "$f" 2>/dev/null || true
done

log_ok "Wine built: wine ($(file "$WINE_INSTALL/bin/wine" | cut -d: -f2 | cut -c1-60))"
log_ok "  PE dlls:  $(ls "$WINE_OUT/wine_dlls/aarch64-windows" | wc -l) aarch64, $(ls "$WINE_OUT/wine_dlls/i386-windows" | wc -l) i386"
log_ok "  unixlib:  $(ls "$WINE_OUT/wine_dlls/aarch64-unix" | wc -l) .so"
log_ok "  dlls size: $(du -sh "$WINE_OUT/wine_dlls" | cut -f1)"

# ---- Verify the display driver made it in -----------------------------------
# winex11.so MUST be present in the unix libs — without it the game can
# never open a window (the exact bug that made v0.1.0 unusable).
if [[ ! -f "$WINE_OUT/wine_dlls/aarch64-unix/winex11.so" ]]; then
    die "winex11.so missing from the staged unix libs — X11 support did not get compiled!"
fi
log_ok "  display driver: winex11.so ✓ ($(du -h "$WINE_OUT/wine_dlls/aarch64-unix/winex11.so" | cut -f1))"

# ---- Stage the X11 client libs next to the runtime --------------------------
# wine dlopens winex11.so which NEEDs libX11.so / libXext.so etc. — they
# must be in the prebuilt tree so build_apk.sh packs them into usr/lib
# (the app sets LD_LIBRARY_PATH=<filesDir>/usr/lib).
log "Staging X11 client libraries ..."
mkdir -p "$WINE_OUT/x11-libs"
for f in libX11.so libX11-xcb.so libxcb.so libXau.so libXdmcp.so libXext.so \
         libXrender.so libXfixes.so libXcursor.so libXrandr.so libXinerama.so \
         libXcomposite.so libXxf86vm.so libXi.so; do
    if [[ -e "$XLIBS_SYSROOT/lib/$f" ]]; then
        cp -fL "$XLIBS_SYSROOT/lib/$f" "$WINE_OUT/x11-libs/$f"
    else
        warn "  X11 lib not staged (missing): $f"
    fi
done
log_ok "  X11 libs staged: $(ls "$WINE_OUT/x11-libs" | wc -l) files"

# ---- Bitmap fonts (host-native side build) ----------------------------------
# Wine's builtin bitmap fonts (vgasys.fon, sserife.fon, smalle.fon, ...) are
# generated from fonts/*.ttf by tools/sfnt2fon — a HOST tool that links
# against libfreetype (tools/sfnt2fon/Makefile.in: UNIX_LIBS = $(FREETYPE_LIBS)).
# Our cross-configure above must pass --without-freetype (Android target has
# no freetype), which sets enable_fonts=no and silently skips the whole
# fonts/ dir — v0.1.1 shipped without fonts, so every wine message box and
# every GDI stock-font text rendered BLANK.
# The build container already ships libfreetype6-dev (see Dockerfile.build),
# so configure a second NATIVE x86_64 tree with the container's own gcc and
# build ONLY sfnt2fon + fonts (~30 s, no X/pulse needed).  The .fon files
# are architecture-independent data, valid for the aarch64 runtime.
# Verified locally against wine-9.0: configure finds libfreetype.so.6,
# `make -C tools/sfnt2fon && make -C fonts` yields 50 .fon + 6 .ttf.
log "Building Wine bitmap fonts (native host tree)..."
FONT_BUILD="$BUILD_DIR/wine-fonts-native"
mkdir -p "$FONT_BUILD"
(
    cd "$FONT_BUILD"
    # Neutralize the NDK cross-exports from setup_android_env — the native
    # tree MUST compile with the container's own gcc, not the NDK clang.
    env -u CC -u CXX -u AR -u RANLIB -u STRIP \
        -u CFLAGS -u CXXFLAGS -u LDFLAGS -u CPPFLAGS \
        -u PKG_CONFIG_PATH -u PKG_CONFIG_LIBDIR \
        CC=gcc CXX=g++ \
        "$WINE_SRC/configure" \
            --enable-win64 \
            --without-x --without-pulse --without-alsa --without-oss \
            --without-sane --without-usb --without-capi --without-netapi \
            --without-gstreamer --without-sdl \
            --disable-win16 --disable-tests
    # The `env -u` shields configure from the NDK cross-exports; the
    # explicit CC/CXX on the make command line is defense-in-depth in case
    # anything re-exports them (Makefile vars normally win over env, but a
    # command-line override removes all doubt).
    make -j"$(nproc)" -C tools/sfnt2fon CC=gcc CXX=g++
    make -j"$(nproc)" -C fonts CC=gcc CXX=g++
) || die "native fonts build failed"
FONTS_OUT="$WINE_OUT/usr/share/wine/fonts"
mkdir -p "$FONTS_OUT"
cp -f  "$FONT_BUILD/fonts/"*.fon "$FONTS_OUT/"
# .ttf (tahoma, marlett, symbol, webdings, wingding) are symlinks into the
# source tree in an out-of-tree build — cp -L dereferences them.
cp -fL "$FONT_BUILD/fonts/"*.ttf "$FONTS_OUT/"
FONT_COUNT=$(ls -1 "$FONTS_OUT" | wc -l)
log_ok "  bitmap fonts staged: $FONT_COUNT files ($(du -sh "$FONTS_OUT" | cut -f1))"
# Wine references these three names from its registry (wine.inf font
# substitutions) — their absence means blank text in wine dialogs.
for must in vgasys.fon sserife.fon smalle.fon; do
    [[ -f "$FONTS_OUT/$must" ]] || die "bitmap font $must missing after build"
done

print_banner "Wine build complete ✓"
