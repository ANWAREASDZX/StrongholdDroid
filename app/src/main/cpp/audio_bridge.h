// audio_bridge.h — StrongholdDroid audio translation layer.
//
// Goal: provide a low-latency audio sink that Wine's PulseAudio driver
// (pulseaudio compiled against Android's libc + OpenSLES / AAudio) can
// connect to. We expose a *named FIFO* on disk and continuously read
// PCM frames from it, pushing them through AAudio in a renderer thread.
//
// Why a FIFO instead of a direct pipe?
//   Wine's libpulse writes via the `pulse-simple` API, which has no native
//   Android backend. Our custom pulseaudio build (see scripts/build_pulse.sh)
//   pipes all sink output through an AF_UNIX socket or a FIFO; the bridge
//   here just picks the bytes off the FIFO and pushes to AAudio.
//
// Latency budget: 30 ms end-to-end (Wine buffer → AAudio).
//   If latency rises above 50 ms during a long session, the FpsMonitor will
//   lower the SFX volume by 3 dB and reduce the wine_pulse buffer count.

#pragma once

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <pthread.h>

#ifdef __cplusplus
extern "C" {
#endif

namespace strongholddroid { namespace audio {

struct AudioConfig {
    uint32_t   sample_rate;     // 44100 for SC SFX, 22050 for SC music
    uint16_t   channels;        // 2 (stereo)
    uint16_t   bits_per_sample; // 16 (S16LE — what Wine Pulse sends)
    uint32_t   buffer_frames;   // ~10 ms at sample_rate; doubled for safety
    bool       use_aaudio;      // true on Android 8.1+, false on 8.0 / OpenSLES
    bool       low_latency;    // AAudio: LOW_LATENCY mode
    const char* fifo_path;      // must match LaunchOptions::audio_pipe_path
};

bool init(JNIEnv* env) noexcept;

// Start the FIFO reader + AAudio renderer thread.
// Returns a session handle (>0) on success, 0 on failure.
int  start_session(const AudioConfig* cfg) noexcept;

// Stop the session and flush. Idempotent.
void stop_session(int session) noexcept;

// Dynamic notification: latency in ms. Called from FpsMonitor.
void notify_latency(int session, uint32_t latency_ms) noexcept;

// Get the AAudio stream's estimated instantaneous latency in microseconds.
uint64_t current_latency_us(int session) noexcept;

void shutdown(JNIEnv* env) noexcept;

}}  // namespace strongholddroid::audio

#ifdef __cplusplus
}  // extern "C"
#endif
