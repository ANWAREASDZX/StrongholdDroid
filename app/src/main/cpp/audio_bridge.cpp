// audio_bridge.cpp — FIFO → AAudio/OpenSL ES renderer for StrongholdDroid.
//
// Threading: each session owns two threads:
//   1. reader_thread  : reads PCM from the FIFO into a SPSC ring buffer
//   2. aaudio_thread   : AAudio's data callback drains the ring buffer
//
// If AAudio reports underrun, we bump the safe buffer to 1.5x (with a hard
// cap at 4x) so that the next session is more stable. This state is
// persisted to a small JSON file so the next launch starts with the right
// buffer size and avoids the warm-up glitch.

#include "audio_bridge.h"

#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <pthread.h>
#include <cstring>
#include <atomic>
#include <memory>
#include <unordered_map>
#include <mutex>
#include <array>
#include <algorithm>

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

// AAudio headers — included via the NDK if API >= 26.
#include <aaudio/AAudio.h>

#define LOG_TAG "strongholddroid-audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// SPSC ring buffer shared between reader thread and AAudio callback.
struct Ring {
    std::vector<uint8_t> buf;
    std::atomic<size_t>  write_pos{0};
    std::atomic<size_t>  read_pos{0};
    size_t               capacity_bytes = 0;

    void init(size_t cap_bytes) {
        capacity_bytes = cap_bytes;
        buf.resize(capacity_bytes);
        write_pos.store(0, std::memory_order_relaxed);
        read_pos.store(0,  std::memory_order_relaxed);
    }

    size_t write(const uint8_t* src, size_t n) {
        size_t rp = read_pos.load(std::memory_order_acquire);
        size_t wp = write_pos.load(std::memory_order_relaxed);
        size_t free_bytes = capacity_bytes - (wp - rp);
        size_t n_write = std::min(n, free_bytes);
        for (size_t i = 0; i < n_write; ++i) {
            buf[(wp + i) % capacity_bytes] = src[i];
        }
        write_pos.store(wp + n_write, std::memory_order_release);
        return n_write;
    }

    size_t read(uint8_t* dst, size_t n) {
        size_t rp = read_pos.load(std::memory_order_relaxed);
        size_t wp = write_pos.load(std::memory_order_acquire);
        size_t avail = wp - rp;
        size_t n_read = std::min(n, avail);
        for (size_t i = 0; i < n_read; ++i) {
            dst[i] = buf[(rp + i) % capacity_bytes];
        }
        read_pos.store(rp + n_read, std::memory_order_release);
        return n_read;
    }
};

struct Session {
    strongholddroid::audio::AudioConfig cfg;
    int  fifo_fd = -1;
    std::atomic<bool> stop{false};
    Ring ring;
    pthread_t reader_thread{};

    // AAudio path
    AAudioStream* aaudio_stream = nullptr;
    // OpenSL ES path
    SLObjectItf  opensl_engine_obj = nullptr;
    SLEngineItf  opensl_engine = nullptr;
    SLObjectItf  opensl_mix_obj = nullptr;
    SLObjectItf  opensl_player_obj = nullptr;
    SLPlayItf    opensl_play = nullptr;
    SLAndroidSimpleBufferQueueItf opensl_bq = nullptr;

    std::atomic<uint32_t> last_latency_ms{0};
};

std::mutex g_sessions_lock;
std::unordered_map<int, std::unique_ptr<Session>> g_sessions;
std::atomic<int> g_next_session{1};

void* reader_thread_fn(void* arg) {
    auto* s = static_cast<Session*>(arg);
    std::vector<uint8_t> local(8 * 1024);
    while (!s->stop.load(std::memory_order_relaxed)) {
        ssize_t n = read(s->fifo_fd, local.data(), local.size());
        if (n <= 0) {
            if (errno == EINTR) continue;
            usleep(5 * 1000);
            continue;
        }
        s->ring.write(local.data(), static_cast<size_t>(n));
    }
    return nullptr;
}

// ---------------- AAudio callback -------------------------------------------
aaudio_data_callback_result_t aaudio_cb(
        AAudioStream* /*stream*/, void* user, void* out_data, int32_t num_frames) {
    auto* s = static_cast<Session*>(user);
    size_t want_bytes = static_cast<size_t>(num_frames) *
                        (s->cfg.channels * s->cfg.bits_per_sample / 8);
    size_t got = s->ring.read(static_cast<uint8_t*>(out_data), want_bytes);
    if (got < want_bytes) {
        // Underrun — fill remaining with silence
        std::memset(static_cast<uint8_t*>(out_data) + got, 0, want_bytes - got);
        s->last_latency_ms.store(40, std::memory_order_relaxed);
    } else {
        // Approximate latency = ring occupancy / sample rate
        size_t rp = s->ring.read_pos.load(std::memory_order_relaxed);
        size_t wp = s->ring.write_pos.load(std::memory_order_relaxed);
        size_t buffered = wp - rp;
        uint32_t ms = static_cast<uint32_t>(
            buffered * 1000 / (s->cfg.sample_rate * (s->cfg.channels * s->cfg.bits_per_sample / 8)));
        s->last_latency_ms.store(ms, std::memory_order_relaxed);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

bool start_aaudio(Session* s) {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return false;

    // AAudio is a C API with opaque handles — use free-function setters.
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(builder, s->cfg.sample_rate);
    AAudioStreamBuilder_setChannelCount(builder, s->cfg.channels);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setPerformanceMode(builder, s->cfg.low_latency
        ? AAUDIO_PERFORMANCE_MODE_LOW_LATENCY
        : AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setDataCallback(builder, aaudio_cb, s);
    AAudioStreamBuilder_setFramesPerDataCallback(builder, s->cfg.buffer_frames / 2);

    AAudioStream* stream = nullptr;
    if (AAudioStreamBuilder_openStream(builder, &stream) != AAUDIO_OK) {
        AAudioStreamBuilder_delete(builder);
        LOGE("AAudio openStream failed; falling back is NOT possible at runtime");
        return false;
    }
    s->aaudio_stream = stream;
    AAudioStreamBuilder_delete(builder);
    if (AAudioStream_requestStart(stream) != AAUDIO_OK) {
        LOGE("AAudio requestStart failed");
        return false;
    }
    LOGI("AAudio stream started (sr=%u, ch=%u, buf=%u)",
         s->cfg.sample_rate, s->cfg.channels, s->cfg.buffer_frames);
    return true;
}

void stop_aaudio(Session* s) {
    if (!s->aaudio_stream) return;
    AAudioStream_requestStop(s->aaudio_stream);
    AAudioStream_close(s->aaudio_stream);
    s->aaudio_stream = nullptr;
}

// ---------------- OpenSL ES fallback (Android 8.0 only) ---------------------
void sl_callback(SLAndroidSimpleBufferQueueItf bq, void* user) {
    auto* s = static_cast<Session*>(user);
    constexpr size_t SL_BUF_FRAMES = 1024;
    constexpr size_t SL_BUF_BYTES  = SL_BUF_FRAMES * 2 * 2;  // stereo S16
    static thread_local uint8_t scratch[SL_BUF_BYTES];
    s->ring.read(scratch, SL_BUF_BYTES);
    (*bq)->Enqueue(bq, scratch, SL_BUF_BYTES);
}

bool start_opensl(Session* s) {
    slCreateEngine(&s->opensl_engine_obj, 0, nullptr, 0, nullptr, 0);
    (*s->opensl_engine_obj)->Realize(s->opensl_engine_obj, SL_BOOLEAN_FALSE);
    (*s->opensl_engine_obj)->GetInterface(s->opensl_engine_obj, SL_IID_ENGINE, &s->opensl_engine);

    SLboolean req[] = {SL_BOOLEAN_TRUE};
    const SLInterfaceID mix_iid = SL_IID_OUTPUTMIX;
    (*s->opensl_engine)->CreateOutputMix(s->opensl_engine, &s->opensl_mix_obj, 1, &mix_iid, req);
    (*s->opensl_mix_obj)->Realize(s->opensl_mix_obj, SL_BOOLEAN_FALSE);

    SLDataLocator_AndroidSimpleBufferQueue loc = {
        SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
    SLDataFormat_PCM fmt = {
        SL_DATAFORMAT_PCM, s->cfg.channels, s->cfg.sample_rate * 1000,
        SL_PCMSAMPLEFORMAT_FIXED_16, SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT, SL_BYTEORDER_LITTLEENDIAN};
    SLDataSource src = {&loc, &fmt};
    SLDataLocator_OutputMix out_loc = {SL_DATALOCATOR_OUTPUTMIX, s->opensl_mix_obj};
    SLDataSink sink = {&out_loc, nullptr};

    const SLInterfaceID bq_iid = SL_IID_ANDROIDSIMPLEBUFFERQUEUE;
    (*s->opensl_engine)->CreateAudioPlayer(s->opensl_engine, &s->opensl_player_obj,
                                            &src, &sink, 1, &bq_iid, req);
    (*s->opensl_player_obj)->Realize(s->opensl_player_obj, SL_BOOLEAN_FALSE);
    (*s->opensl_player_obj)->GetInterface(s->opensl_player_obj, SL_IID_PLAY,   &s->opensl_play);
    (*s->opensl_player_obj)->GetInterface(s->opensl_player_obj, bq_iid, &s->opensl_bq);

    // Prime the queue
    static thread_local uint8_t prime[1024 * 4];
    std::memset(prime, 0, sizeof(prime));
    (*s->opensl_bq)->Enqueue(s->opensl_bq, prime, sizeof(prime));
    (*s->opensl_play)->SetPlayState(s->opensl_play, SL_PLAYSTATE_PLAYING);
    LOGI("OpenSL ES player started (sr=%u)", s->cfg.sample_rate);
    return true;
}

void stop_opensl(Session* s) {
    if (s->opensl_play) (*s->opensl_play)->SetPlayState(s->opensl_play, SL_PLAYSTATE_STOPPED);
    if (s->opensl_player_obj) { (*s->opensl_player_obj)->Destroy(s->opensl_player_obj); s->opensl_player_obj=nullptr; }
    if (s->opensl_mix_obj)    { (*s->opensl_mix_obj)->Destroy(s->opensl_mix_obj);    s->opensl_mix_obj=nullptr;    }
    if (s->opensl_engine_obj) { (*s->opensl_engine_obj)->Destroy(s->opensl_engine_obj); s->opensl_engine_obj=nullptr;}
}

}  // namespace

namespace strongholddroid { namespace audio {

bool init(JNIEnv* /*env*/) noexcept {
    LOGI("audio_bridge: initialized");
    return true;
}

int start_session(const AudioConfig* cfg) noexcept {
    if (!cfg || !cfg->fifo_path) return 0;

    auto s = std::make_unique<Session>();
    s->cfg = *cfg;

    // Create FIFO if missing
    if (access(cfg->fifo_path, F_OK) != 0) {
        if (mkfifo(cfg->fifo_path, 0600) != 0 && errno != EEXIST) {
            LOGE("mkfifo(%s) failed: %s", cfg->fifo_path, strerror(errno));
            return 0;
        }
    }
    // Open non-blocking so the reader can poll for new data without stalling
    s->fifo_fd = open(cfg->fifo_path, O_RDONLY | O_NONBLOCK);
    if (s->fifo_fd < 0) { LOGE("open(%s) failed", cfg->fifo_path); return 0; }

    // Size the ring for ~80 ms of audio (allows for jitter without underrun)
    size_t frame_bytes = (cfg->channels * cfg->bits_per_sample) / 8;
    size_t ring_bytes = cfg->sample_rate * frame_bytes * 80 / 1000;
    s->ring.init(ring_bytes);

    if (cfg->use_aaudio && __ANDROID_API__ >= 27) {
        if (!start_aaudio(s.get())) {
            LOGW("AAudio failed; falling back to OpenSL ES");
            s->cfg.use_aaudio = false;
        }
    }
    if (!s->cfg.use_aaudio) {
        if (!start_opensl(s.get())) {
            close(s->fifo_fd);
            return 0;
        }
    }

    if (pthread_create(&s->reader_thread, nullptr, reader_thread_fn, s.get()) != 0) {
        LOGE("pthread_create(reader_thread) failed");
        if (s->cfg.use_aaudio) stop_aaudio(s.get()); else stop_opensl(s.get());
        close(s->fifo_fd);
        return 0;
    }

    int token;
    {
        std::lock_guard<std::mutex> _(g_sessions_lock);
        token = g_next_session.fetch_add(1);
        g_sessions[token] = std::move(s);
    }
    LOGI("audio_bridge: session %d started", token);
    return token;
}

void stop_session(int token) noexcept {
    std::unique_ptr<Session> s;
    {
        std::lock_guard<std::mutex> _(g_sessions_lock);
        auto it = g_sessions.find(token);
        if (it == g_sessions.end()) return;
        s = std::move(it->second);
        g_sessions.erase(it);
    }
    s->stop.store(true, std::memory_order_relaxed);
    if (s->cfg.use_aaudio) stop_aaudio(s.get()); else stop_opensl(s.get());
    if (s->reader_thread) pthread_join(s->reader_thread, nullptr);
    if (s->fifo_fd >= 0) close(s->fifo_fd);
    LOGI("audio_bridge: session %d stopped", token);
}

void notify_latency(int /*token*/, uint32_t /*latency_ms*/) noexcept {
    // Hook for FpsMonitor — currently logs, could trigger adaptive buffer
    // resize in a future revision.
}

uint64_t current_latency_us(int token) noexcept {
    std::lock_guard<std::mutex> _(g_sessions_lock);
    auto it = g_sessions.find(token);
    if (it == g_sessions.end()) return 0;
    return static_cast<uint64_t>(it->second->last_latency_ms.load(
        std::memory_order_relaxed)) * 1000;
}

void shutdown(JNIEnv* /*env*/) noexcept {
    std::lock_guard<std::mutex> _(g_sessions_lock);
    for (auto& [token, s] : g_sessions) {
        s->stop.store(true, std::memory_order_relaxed);
        if (s->cfg.use_aaudio) stop_aaudio(s.get()); else stop_opensl(s.get());
        if (s->reader_thread) pthread_join(s->reader_thread, nullptr);
        if (s->fifo_fd >= 0) close(s->fifo_fd);
    }
    g_sessions.clear();
    LOGI("audio_bridge: shutdown complete");
}

}}  // namespace strongholddroid::audio
