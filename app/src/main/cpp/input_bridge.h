// input_bridge.h — StrongholdDroid input translation layer.
//
// The RTS overlay (RtsControlOverlay.kt) and the GamepadMapper feed raw
// MotionEvent-derived packets into this bridge via JNI. The bridge then:
//   • Maintains a virtual mouse position (single absolute pointer)
//   • Maintains a virtual keyboard state (256-bit bitmask)
//   • Pushes these into Wine's internal event queue via the
//     `wine_user_input_event` symbol that the prebuilt libwine.so exposes.
//
// All packets are dispatched on the JVM's main thread; the bridge itself
// is lock-free and safe to call concurrently from multiple MotionEvents.

#pragma once

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

namespace strongholddroid { namespace input {

enum class EventKind : uint8_t {
    MouseMove      = 1,
    MouseDown      = 2,
    MouseUp        = 3,
    MouseWheel     = 4,
    KeyDown        = 5,
    KeyUp          = 6,
    CharTyped      = 7,
    GestureZoom    = 8,
    GesturePan     = 9,
    GestureRotate  = 10,
};

struct Packet {
    EventKind kind;
    int32_t   pointer_id;     // 0 = primary mouse, 1..N = secondary touches
    float     x_norm;         // 0.0..1.0 of game surface width
    float     y_norm;         // 0.0..1.0 of game surface height
    int32_t   button_mask;    // bitfield: 1=left, 2=right, 4=middle, 8=x1, 16=x2
    int32_t   vkey_code;      // Windows VK_* code, e.g. 0x09 TAB
    float     delta_x;        // for wheel / pan / zoom / rotate
    float     delta_y;
    int64_t   timestamp_ns;
};

bool init(JNIEnv* env) noexcept;
void shutdown(JNIEnv* env) noexcept;

// Enqueue a packet. Safe to call from the UI thread at >1 kHz if needed.
// Returns true if accepted, false if the queue is full (caller should
// coalesce; currently the queue holds 256 packets).
bool enqueue_packet(const Packet* pkt) noexcept;

// Pump all queued packets into Wine. Called on each frame from a thread
// attached to the JVM that is *not* the UI thread (the EmulatorCore
// owns a pump loop).
void pump_into_wine() noexcept;

// Direct query for the virtual mouse position (used by SaveState to embed
// mouse state in screenshots).
void get_mouse_position(int* x_out, int* y_out) noexcept;

}}  // namespace strongholddroid::input

#ifdef __cplusplus
}  // extern "C"
#endif
