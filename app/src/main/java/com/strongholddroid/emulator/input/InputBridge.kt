package com.strongholddroid.emulator.input

import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicLong

/**
 * Kotlin-side façade for the native `input_bridge.cpp`. Exposes a clean API
 * for the RTS overlay and GamepadMapper to push input packets without
 * having to know about the SPSC queue or the Wine symbol marshalling.
 *
 * Threading: enqueues are lock-free and safe from any thread. The single
 * consumer is the [com.strongholddroid.emulator.emulator.EmulatorCore]
 * pump loop, which calls [pumpIntoWine] at ~240 Hz.
 */
object InputBridge {

    // Mirror of input_bridge.h's EventKind. Must stay in sync.
    const val KIND_MOUSE_MOVE     = 1
    const val KIND_MOUSE_DOWN     = 2
    const val KIND_MOUSE_UP       = 3
    const val KIND_MOUSE_WHEEL    = 4
    const val KIND_KEY_DOWN       = 5
    const val KIND_KEY_UP         = 6
    const val KIND_CHAR_TYPED     = 7
    const val KIND_GESTURE_ZOOM   = 8
    const val KIND_GESTURE_PAN    = 9
    const val KIND_GESTURE_ROTATE = 10

    // Windows button bitfield values (see winuser.h MK_*).
    const val BTN_LEFT   = 0x01
    const val BTN_RIGHT  = 0x02
    const val BTN_MIDDLE = 0x04
    const val BTN_X1     = 0x08
    const val BTN_X2     = 0x10

    private val seq = AtomicLong(0)

    /** Enqueue a mouse move at normalized (0..1) screen coords. */
    fun mouseMove(pointerId: Int, xNorm: Float, yNorm: Float) {
        enqueue(KIND_MOUSE_MOVE, pointerId, xNorm, yNorm, 0, 0, 0f, 0f)
    }

    /** Enqueue a mouse-button transition (buttonMask = OR of BTN_*). */
    fun mouseButton(pointerId: Int, down: Boolean, buttonMask: Int,
                    xNorm: Float, yNorm: Float) {
        val kind = if (down) KIND_MOUSE_DOWN else KIND_MOUSE_UP
        enqueue(kind, pointerId, xNorm, yNorm, buttonMask, 0, 0f, 0f)
    }

    /** Mouse wheel scroll — delta in "notches" (positive = up). */
    fun mouseWheel(pointerId: Int, xNorm: Float, yNorm: Float, dy: Float) {
        enqueue(KIND_MOUSE_WHEEL, pointerId, xNorm, yNorm, 0, 0, 0f, dy)
    }

    /** Translate Android [KeyEvent] to a Wine VK_* code and enqueue. */
    fun keyFromAndroid(event: KeyEvent) {
        val vk = VkTranslator.androidToVk(event)
        if (vk == 0) return
        val kind = if (event.action == KeyEvent.ACTION_DOWN) KIND_KEY_DOWN
                   else if (event.action == KeyEvent.ACTION_UP)   KIND_KEY_UP
                   else return
        enqueue(kind, 0, 0f, 0f, 0, vk, 0f, 0f)
    }

    /** Pump queued packets into Wine. Called from EmulatorCore at ~240 Hz. */
    fun pumpIntoWine() = nativeInputPump()

    private fun enqueue(kind: Int, pointerId: Int, xNorm: Float, yNorm: Float,
                        buttonMask: Int, vkeyCode: Int, dx: Float, dy: Float) {
        val ts = SystemClock.uptimeMillis() * 1_000_000L
        if (!nativeInputEnqueue(kind, pointerId, xNorm, yNorm, buttonMask,
                                  vkeyCode, dx, dy, ts)) {
            // Queue full — caller (RTS overlay) coalesces moves already;
            // for buttons we always accept, so silence here is fine.
        }
    }

    // JNI bindings.
    @Keep private external fun nativeInputEnqueue(
        kind: Int, pointerId: Int, xNorm: Float, yNorm: Float,
        buttonMask: Int, vkeyCode: Int, dx: Float, dy: Float,
        timestampNs: Long): Boolean
    @Keep private external fun nativeInputPump()
}
