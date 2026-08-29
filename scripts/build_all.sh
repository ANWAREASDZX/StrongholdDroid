#!/usr/bin/env bash
#
# build_all.sh — top-level orchestrator. Runs each component's build script
# in dependency order, then validates the resulting prebuilt tree.
#
# Output: app/src/main/cpp/prebuilt/arm64-v8a/ with:
#   libwine/wine, libwine/wineserver        (arm64 ELF, run natively)
#   wine_dlls/aarch64-windows/              (native 64-bit builtin PE DLLs)
#   wine_dlls/i386-windows/                 (WoW64 32-bit builtin PE DLLs)
#   wine_dlls/aarch64-unix/                 (unix-side .so wine dlopens)
#   wow64/wowbox64.dll                      (box64 WoW64 cpu dll — staged
#                                            as xtajit.dll at runtime)
#   libpulse.so (+ lib/), libpulsecommon-*.so
#   libGL.so, libGL.so.1                    (gl4es GLES translation)
#   dxvk-wine-dlls/*.dll                    (32-bit DXVK for D3D8/9 titles)
#
# Use as the entry point in CI (see .circleci/config.yml).
#
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"

print_banner "StrongholdDroid full native build"

START_TS=$(date +%s)

# ---- Step 1: toolchain -----------------------------------------------------
log "Step 1/6: ensure toolchain"
bash "$SCRIPTS_DIR/setup_toolchain.sh"

# ---- Step 2: PulseAudio (Wine needs libpulse at link time) ----------------
log "Step 2/6: build PulseAudio"
bash "$SCRIPTS_DIR/build_pulse.sh"

# ---- Step 3: Wine (needs libpulse) ----------------------------------------
log "Step 3/6: build Wine"
bash "$SCRIPTS_DIR/build_wine.sh"

# ---- Step 4: box64 WoW64 cpu dll (32-bit x86 execution) --------------------
log "Step 4/6: build box64 WoW64 cpu dll"
bash "$SCRIPTS_DIR/build_box64.sh"

# ---- Step 5: gl4es (fallback graphics) -------------------------------------
log "Step 5/6: build gl4es (fallback graphics)"
bash "$SCRIPTS_DIR/build_gl4es.sh"

# ---- Step 6: DXVK MinGW DLLs (needs mingw + glslang) -----------------------
log "Step 6/6: build DXVK MinGW DLLs"
bash "$SCRIPTS_DIR/build_dxvk.sh"

# ---- Validation ------------------------------------------------------------
log "Validating prebuilt tree..."
EXPECTED=(
    "libpulse.so"
    "libGL.so"
    "libwine/wine"
    "libwine/wineserver"
    "wow64/wowbox64.dll"
    "wine_dlls/aarch64-windows/kernel32.dll"
    "wine_dlls/i386-windows/kernel32.dll"
    "wine_dlls/aarch64-unix/ntdll.so"
)
MISSING=0
# libpulsecommon-<PA-version>.so — version-suffixed name, glob-match it.
PULSECOMMON_COUNT=$(ls "$PREBUILT_DIR/arm64-v8a"/libpulsecommon-*.so 2>/dev/null | wc -l)
if [[ "$PULSECOMMON_COUNT" -eq 0 ]]; then
    warn "  MISSING: libpulsecommon-*.so (libpulse.so NEEDEDs it at runtime)"
    MISSING=$((MISSING+1))
fi
# DXVK dlls — at least one .dll must be present.
DXVK_COUNT=$(ls "$PREBUILT_DIR/arm64-v8a/dxvk-wine-dlls/"*.dll 2>/dev/null | wc -l)
if [[ "$DXVK_COUNT" -eq 0 ]]; then
    warn "  MISSING: dxvk-wine-dlls/*.dll"
    MISSING=$((MISSING+1))
fi
for f in "${EXPECTED[@]}"; do
    if [[ ! -f "$PREBUILT_DIR/arm64-v8a/$f" ]]; then
        warn "  MISSING: $f"
        MISSING=$((MISSING+1))
    fi
done

if [[ "$MISSING" -gt 0 ]]; then
    die "Build incomplete — $MISSING file(s) missing"
fi

log "  DXVK DLLs available at $PREBUILT_DIR/arm64-v8a/dxvk-wine-dlls:"
ls -1 "$PREBUILT_DIR/arm64-v8a/dxvk-wine-dlls" | sed 's/^/    - /'

ELAPSED=$(( $(date +%s) - START_TS ))
log_ok "Full native build complete in ${ELAPSED}s"
log_ok "Prebuilt artifacts at: $PREBUILT_DIR/arm64-v8a/"
