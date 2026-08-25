// input_bridge.cpp — StrongholdDroid input translation (impl).
//
// Lock-free SPSC queue (capacity 256 packets). Producers are UI thread +
// gamepad thread; consumer is the EmulatorCore pump thread.
//
// Virtual mouse state lives in two atomic ints (x, y) in 0..65535 fixed
// point (16.16) — we convert from the float-normalized packet coords on
// enqueue. This makes pump_into_wine() fast and branch-free.

#include "input_bridge.h"

#include <android/log.h>
#include <atomic>
#include <cstring>
#include <cstdint>
#include <array>

#define LOG_TAG "strongholddroid-input"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

// Symbol from prebuilt libwine.so. Strongly-typed prototype.
extern "C" __attribute__((weak)) void wine_user_input_event(
    int kind, int x, int y, int button_mask, int vkey, int delta);

namespace {

constexpr size_t QUEUE_CAP = 256;

struct alignas(64) Queue {
    std::array<strongholddroid::input::Packet, QUEUE_CAP> buf;
    std::atomic<uint32_t> head{0};  // producer
    std::atomic<uint32_t> tail{0};  // consumer
} g_queue;

// Virtual mouse in 16.16 fixed point, absolute coords in [0, 65536).
std::atomic<int32_t> g_mouse_x{0};
std::atomic<int32_t> g_mouse_y{0};
std::atomic<int32_t> g_button_mask{0};

inline int32_t to_fixed(float v) {
    if (v <= 0.0f) return 0;
    if (v >= 1.0f) return 0xFFFF;
    return static_cast<int32_t>(v * 65536.0f);
}

}  // namespace

namespace strongholddroid { namespace input {

bool init(JNIEnv* /*env*/) noexcept {
    g_queue.head.store(0, std::memory_order_relaxed);
    g_queue.tail.store(0, std::memory_order_relaxed);
    g_mouse_x.store(0, std::memory_order_relaxed);
    g_mouse_y.store(0, std::memory_order_relaxed);
    g_button_mask.store(0, std::memory_order_relaxed);
    LOGI("input_bridge: initialized (queue cap=%zu)", QUEUE_CAP);
    return true;
}

void shutdown(JNIEnv* /*env*/) noexcept {
    g_queue.head.store(0, std::memory_order_relaxed);
    g_queue.tail.store(0, std::memory_order_relaxed);
}

bool enqueue_packet(const Packet* pkt) noexcept {
    if (!pkt) return false;

    uint32_t head = g_queue.head.load(std::memory_order_relaxed);
    uint32_t next = (head + 1) % QUEUE_CAP;
    uint32_t tail = g_queue.tail.load(std::memory_order_acquire);
    if (next == tail) {
        // Queue full — drop oldest (advance tail) so latest input survives
        g_queue.tail.store((tail + 1) % QUEUE_CAP, std::memory_order_release);
    }
    g_queue.buf[head] = *pkt;
    g_queue.head.store(next, std::memory_order_release);

    // Update virtual mouse state immediately so get_mouse_position is fresh
    if (pkt->kind == EventKind::MouseMove || pkt->kind == EventKind::MouseDown
        || pkt->kind == EventKind::MouseUp) {
        g_mouse_x.store(to_fixed(pkt->x_norm), std::memory_order_relaxed);
        g_mouse_y.store(to_fixed(pkt->y_norm), std::memory_order_relaxed);
    }
    if (pkt->kind == EventKind::MouseDown) {
        uint32_t cur = g_button_mask.load(std::memory_order_relaxed);
        g_button_mask.store(cur | pkt->button_mask, std::memory_order_relaxed);
    } else if (pkt->kind == EventKind::MouseUp) {
        uint32_t cur = g_button_mask.load(std::memory_order_relaxed);
        g_button_mask.store(cur & ~pkt->button_mask, std::memory_order_relaxed);
    }
    return true;
}

void pump_into_wine() noexcept {
    if (!wine_user_input_event) return;  // weak symbol not present (e.g. unit test)

    uint32_t tail = g_queue.tail.load(std::memory_order_relaxed);
    uint32_t head = g_queue.head.load(std::memory_order_acquire);
    while (tail != head) {
        const Packet& p = g_queue.buf[tail];
        // Convert 16.16 fixed mouse position back to Win coords (0..65535).
        int x = g_mouse_x.load(std::memory_order_relaxed) >> 1;
        int y = g_mouse_y.load(std::memory_order_relaxed) >> 1;
        int delta = 0;
        if (p.kind == EventKind::MouseWheel) {
            delta = static_cast<int>(-p.delta_y * 120.0f);
        }
        wine_user_input_event(static_cast<int>(p.kind), x, y,
                              g_button_mask.load(std::memory_order_relaxed),
                              p.vkey_code, delta);
        tail = (tail + 1) % QUEUE_CAP;
    }
    g_queue.tail.store(tail, std::memory_order_release);
}

void get_mouse_position(int* x_out, int* y_out) noexcept {
    if (x_out) *x_out = g_mouse_x.load(std::memory_order_relaxed) >> 1;
    if (y_out) *y_out = g_mouse_y.load(std::memory_order_relaxed) >> 1;
}

}}  // namespace strongholddroid::input
