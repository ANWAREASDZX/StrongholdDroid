// wine_bridge.h — StrongholdDroid Wine/Box64 process supervisor.
//
// Responsibilities:
//   • Spawn wine64 as a child process under box64 (or directly on x86_64 hosts)
//   • Set up the WINEPREFIX at runtime (so per-game prefixes are isolated)
//   • Pipe stdout/stderr back to Android logcat so crashes are debuggable
//   • Detect clean vs. abnormal exit and propagate to Kotlin
//
// All functions here are pure C — they are invoked from JNI in native_jni.cpp
// and called on a dedicated worker thread attached to the JVM.

#pragma once

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

namespace strongholddroid { namespace wine {

// Global one-time initialization. Idempotent.
// Must be called from JNI_OnLoad with the env attached.
bool init(JNIEnv* env) noexcept;

// Per-game shutdown — kills any running Wine process, closes pipes.
void shutdown(JNIEnv* env) noexcept;

struct LaunchOptions {
    const char* wine_bin_path;        // e.g. /data/.../usr/bin/wine64
    const char* wine_prefix;          // e.g. /data/.../prefixes/sc-1.1
    const char* winetricks_cache;    // dir for winetricks downloads
    const char* game_exec;            // e.g. C:\Stronghold Crusader\Stronghold_Crusader.exe
    const char** env_kv;              // KEY=VAL pairs, env_kv_count entries
    size_t      env_kv_count;
    const char* wine_arch;            // "win64" (with WoW64) or "win32"
    bool        wow64_enabled;        // enable new WoW64 thunking in Wine 8+
    bool        esync_enabled;        // eventfd-based synchronization
    bool        fsync_enabled;        // futex-based synchronization
    uint32_t    render_target_width;  // 0 = desktop native
    uint32_t    render_target_height;
    const char* audio_pipe_path;      // FIFO that the AudioBridge writes to
};

// Returns the PID (>0) on success, 0 on transient failure (retry),
// or -1 on hard failure (see last_error()).
int launch_game(const LaunchOptions* opts) noexcept;

// Block until the game process exits, then return its exit code.
// 30s timeout for graceful shutdown, after which SIGKILL is sent.
int wait_for_exit(int pid) noexcept;

// Request a polite shutdown (SIGTERM to wine64, SIGTERM to wineserver).
void request_shutdown(int pid) noexcept;

// Forcibly kill (SIGKILL the entire process group).
void force_kill(int pid) noexcept;

// Return a copy of the last error message (thread-local). Never null.
const char* last_error() noexcept;

}}  // namespace strongholddroid::wine

#ifdef __cplusplus
}  // extern "C"
#endif
