#!/usr/bin/env bash
#
# build_all.sh — top-level orchestrator. Runs each component's build script
# in dependency order, then validates the resulting prebuilt tree.
#
# Output: app/src/main/cpp/prebuilt/arm64-v8a/ with:
#   libwine.so, libwine.so.1, libbox64.so, libpulse.so, libGL.so,
#   libdxvk_loader.so,
#   libwine/wine64, libwine/wineserver
#   dxvk-wine-dlls/{d3d9,d3d11,dxgi,d3dcompiler_47}.dll
#   wine_dlls/*.dll (Wine builtin DLLs)
#
# Use as the entry point in CI (see .github/workflows/build.yml).
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

# ---- Step 4: Box64 (independent) ------------------------------------------
log "Step 4/6: build Box64"
bash "$SCRIPTS_DIR/build_box64.sh"

# ---- Step 5: gl4es (independent) ------------------------------------------
log "Step 5/6: build gl4es (fallback graphics)"
bash "$SCRIPTS_DIR/build_gl4es.sh"

# ---- Step 6: DXVK + Vulkan loader (needs Wine headers from step 3) -------
log "Step 6/6: build DXVK + Vulkan loader"
bash "$SCRIPTS_DIR/build_dxvk.sh"

# ---- Validation ------------------------------------------------------------
log "Validating prebuilt tree..."
EXPECTED=(
    "libwine.so"
    "libwine.so.1"
    "libbox64.so"
    "libpulse.so.0"
    "libGL.so"
    "libdxvk_loader.so"
    "libwine/wine64"
    "libwine/wineserver"
)
MISSING=0
for f in "${EXPECTED[@]}"; do
    if [[ ! -f "$PREBUILT_DIR/arm64-v8a/$f" ]]; then
        warn "  MISSING: $f"
        MISSING=$((MISSING+1))
    fi
done

if [[ "$MISSING" -gt 0 ]]; then
    die "Build incomplete — $MISSING file(s) missing"
fi

# Copy DLLs to dxvk staging area
DXVK_FINAL="$PREBUILT_DIR/arm64-v8a/dxvk-wine-dlls"
if [[ -d "$DXVK_FINAL" ]]; then
    log "  DXVK DLLs available at $DXVK_FINAL"
    ls -1 "$DXVK_FINAL" | sed 's/^/    - /'
fi

ELAPSED=$(( $(date +%s) - START_TS ))
log_ok "Full native build complete in ${ELAPSED}s"
log_ok "Prebuilt artifacts at: $PREBUILT_DIR/arm64-v8a/"
