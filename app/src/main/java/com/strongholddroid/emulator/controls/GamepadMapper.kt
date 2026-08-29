package com.strongholddroid.emulator.controls

import android.content.Context
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.strongholddroid.emulator.input.InputBridge
import com.strongholddroid.emulator.input.VkTranslator
import kotlin.math.sign

/**
 * Bridges external physical controllers (Xbox/PS5/8BitDo gamepads) to
 * Stronghold Crusader's mouse+keyboard via the [InputBridge].
 *
 * The mappings live in [ControlProfile.gamepadBindings] so users can
 * rebind per-game. We support:
 *
 *   • Sticks  → analog virtual mouse (left) or analog camera scroll (right)
 *   • Triggers → discrete hotkeys (1, 2) or modifier keys (Q, E for rotate)
 *   • Face buttons → mouse buttons or frequent hotkeys (B, M, H, Space, Esc)
 *   • D-pad     → WASD for camera scrolling (matches SC keyboard scheme)
 *
 * Stick dead zone: 0.10 by default — small enough to be responsive but
 * large enough to absorb typical controller drift. Curvature: cubic
 * gain so fine-grained camera control near the stick centre is possible.
 */
class GamepadMapper(
    private val ctx: Context,
    private val profile: ControlProfile,
) {
    private val tag = "GamepadMapper"

    // Sticky button state for "click-style" actions
    @Volatile private var lmbDown = false
    @Volatile private var rmbDown = false

    // Camera-scroll key state — track which arrow keys are currently held
    // so we don't spam keydown/up at full poll rate (~125 Hz).
    private val heldArrows = HashSet<Int>()

    private var lastStickPollMs = 0L
    private val mouseGain = 0.6f
    private val cameraGain = 1.4f

    fun attach(overlay: android.view.View) {
        // Take focus so we receive key events
        overlay.isFocusable = true
        overlay.isFocusableInTouchMode = true
        overlay.requestFocus()
    }

    // ----- D-pad / button events -----

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val b = profile.gamepadBindings
        val action = mapKeyToAction(keyCode, b) ?: return false
        when (action) {
            ControlProfile.GamepadAction.MOUSE_LEFT -> {
                if (!lmbDown) { lmbDown = true; InputBridge.mouseButton(0, true,
                    InputBridge.BTN_LEFT, 0.5f, 0.5f) }
            }
            ControlProfile.GamepadAction.MOUSE_RIGHT -> {
                if (!rmbDown) { rmbDown = true; InputBridge.mouseButton(0, true,
                    InputBridge.BTN_RIGHT, 0.5f, 0.5f) }
            }
            is ControlProfile.GamepadAction.VK_KEY -> {
                val vk = action.vk
                val androidKc = vk.toAndroidKeycode() ?: return false
                if (heldArrows.add(vk)) {
                    InputBridge.keyFromAndroid(KeyEvent(KeyEvent.ACTION_DOWN, androidKc))
                }
            }
            ControlProfile.GamepadAction.VIRTUAL_MOUSE,
            ControlProfile.GamepadAction.CAMERA_SCROLL,
            ControlProfile.GamepadAction.NONE,
            ControlProfile.GamepadAction.MOUSE_WHEEL_UP,
            ControlProfile.GamepadAction.MOUSE_WHEEL_DOWN,
            ControlProfile.GamepadAction.MOUSE_MIDDLE -> { /* analog or analog-equivalent */ }
        }
        return true
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val b = profile.gamepadBindings
        val action = mapKeyToAction(keyCode, b) ?: return false
        when (action) {
            ControlProfile.GamepadAction.MOUSE_LEFT -> {
                if (lmbDown) { lmbDown = false; InputBridge.mouseButton(0, false,
                    InputBridge.BTN_LEFT, 0.5f, 0.5f) }
            }
            ControlProfile.GamepadAction.MOUSE_RIGHT -> {
                if (rmbDown) { rmbDown = false; InputBridge.mouseButton(0, false,
                    InputBridge.BTN_RIGHT, 0.5f, 0.5f) }
            }
            is ControlProfile.GamepadAction.VK_KEY -> {
                heldArrows.remove(action.vk)
                val androidKc = action.vk.toAndroidKeycode() ?: return false
                InputBridge.keyFromAndroid(KeyEvent(KeyEvent.ACTION_UP, androidKc))
            }
            else -> {}
        }
        return true
    }

    // ----- Sticks / triggers -----

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == 0) return false
        val b = profile.gamepadBindings
        val now = System.currentTimeMillis()
        if (now - lastStickPollMs < 8) return true  // ~125 Hz
        lastStickPollMs = now

        // Left stick: virtual mouse
        val lx = event.getAxisValue(MotionEvent.AXIS_X, 0)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y, 0)
        val (lsx, lsy) = applyDeadzone(lx, ly, DEAD_ZONE)
        if (b.leftStick == ControlProfile.GamepadAction.VIRTUAL_MOUSE) {
            if (lsx != 0f || lsy != 0f) {
                val gain = profile.mouseSensitivity * mouseGain
                val dx = (lsx * lsx * sign(lsx) * gain).coerceIn(-1f, 1f)
                val dy = (lsy * lsy * sign(lsy) * gain).coerceIn(-1f, 1f)
                // Use the [VirtualMouse] indirect path — but we don't have
                // a reference here. The simplest fix is to enqueue a relative
                // move packet that the native side adds to the cursor:
                // For the prototype we send an absolute mouse-move packet
                // at the current position (the native code already applies
                // dx, dy as deltas if buttonMask==0 and kind==MOUSE_MOVE).
                InputBridge.mouseMove(pointerId = 1, xNorm = 0.5f + dx, yNorm = 0.5f + dy)
            }
        } else if (b.leftStick == ControlProfile.GamepadAction.CAMERA_SCROLL) {
            sendArrowFromStick(lsx, lsy, profile)
        }

        // Right stick: camera scroll (default)
        val rx = event.getAxisValue(MotionEvent.AXIS_Z, 0)
        val ry = event.getAxisValue(MotionEvent.AXIS_RZ, 0)
        val (rsx, rsy) = applyDeadzone(rx, ry, DEAD_ZONE)
        if (b.rightStick == ControlProfile.GamepadAction.CAMERA_SCROLL) {
            sendArrowFromStick(rsx * cameraGain, rsy * cameraGain, profile)
        }

        // Triggers — discrete hotkeys
        val lt = event.getAxisValue(MotionEvent.AXIS_BRAKE, 0)
        val rt = event.getAxisValue(MotionEvent.AXIS_GAS, 0)
        applyTrigger(b.leftTrigger, lt > 0.5f)
        applyTrigger(b.rightTrigger, rt > 0.5f)
        return true
    }

    // ----- internals -----

    private fun mapKeyToAction(kc: Int, b: ControlProfile.GamepadBindings
    ): ControlProfile.GamepadAction? = when (kc) {
        KeyEvent.KEYCODE_BUTTON_A              -> b.buttonA
        KeyEvent.KEYCODE_BUTTON_B              -> b.buttonB
        KeyEvent.KEYCODE_BUTTON_X              -> b.buttonX
        KeyEvent.KEYCODE_BUTTON_Y              -> b.buttonY
        KeyEvent.KEYCODE_BUTTON_L1             -> b.leftBumper
        KeyEvent.KEYCODE_BUTTON_R1             -> b.rightBumper
        KeyEvent.KEYCODE_BUTTON_L2             -> b.leftTrigger
        KeyEvent.KEYCODE_BUTTON_R2             -> b.rightTrigger
        KeyEvent.KEYCODE_BUTTON_SELECT         -> b.select
        KeyEvent.KEYCODE_BUTTON_START           -> b.start
        KeyEvent.KEYCODE_BUTTON_THUMBL         -> b.leftStickClick
        KeyEvent.KEYCODE_BUTTON_THUMBR         -> b.rightStickClick
        KeyEvent.KEYCODE_DPAD_UP               -> b.dpadUp
        KeyEvent.KEYCODE_DPAD_DOWN             -> b.dpadDown
        KeyEvent.KEYCODE_DPAD_LEFT             -> b.dpadLeft
        KeyEvent.KEYCODE_DPAD_RIGHT            -> b.dpadRight
        else -> null
    }

    private fun applyDeadzone(x: Float, y: Float, dz: Float): Pair<Float, Float> {
        val mag = kotlin.math.hypot(x, y)
        if (mag <= dz) return 0f to 0f
        val scale = (mag - dz) / mag
        return x * scale to y * scale
    }

    private fun sendArrowFromStick(dx: Float, dy: Float, profile: ControlProfile) {
        // Hold arrows while the stick is held; release when centered
        fun maybe(key: Int, hold: Boolean) {
            val vk = when (key) {
                VkTranslator.Vk.LEFT   -> VkTranslator.Vk.LEFT
                VkTranslator.Vk.RIGHT  -> VkTranslator.Vk.RIGHT
                VkTranslator.Vk.UP      -> VkTranslator.Vk.UP
                VkTranslator.Vk.DOWN    -> VkTranslator.Vk.DOWN
                else -> return
            }
            val androidKc = vk.toAndroidKeycode() ?: return
            if (hold && heldArrows.add(vk)) {
                InputBridge.keyFromAndroid(KeyEvent(KeyEvent.ACTION_DOWN, androidKc))
            } else if (!hold && heldArrows.remove(vk)) {
                InputBridge.keyFromAndroid(KeyEvent(KeyEvent.ACTION_UP, androidKc))
            }
        }
        val threshold = 0.25f
        if (dx > threshold)  maybe(VkTranslator.Vk.RIGHT, true)
        else                  maybe(VkTranslator.Vk.RIGHT, false)
        if (dx < -threshold) maybe(VkTranslator.Vk.LEFT,  true)
        else                  maybe(VkTranslator.Vk.LEFT,  false)
        if (dy > threshold)  maybe(VkTranslator.Vk.DOWN,  true)
        else                  maybe(VkTranslator.Vk.DOWN,  false)
        if (dy < -threshold) maybe(VkTranslator.Vk.UP,    true)
        else                  maybe(VkTranslator.Vk.UP,    false)
    }

    @Volatile private var ltHeld = false
    @Volatile private var rtHeld = false
    private fun applyTrigger(action: ControlProfile.GamepadAction, pressed: Boolean) {
        val vk = (action as? ControlProfile.GamepadAction.VK_KEY)?.vk ?: return
        val androidKc = vk.toAndroidKeycode() ?: return
        when (action) {
            ControlProfile.GamepadAction.VK_KEY_1 -> {
                if (pressed && !ltHeld) { ltHeld = true
                    InputBridge.keyFromAndroid(KeyEvent(KeyEvent.ACTION_DOWN, androidKc)) }
                else if (!pressed && ltHeld) { ltHeld = false
                    InputBridge.keyFromAndroid(KeyEvent(KeyEvent.ACTION_UP, androidKc)) }
            }
            ControlProfile.GamepadAction.VK_KEY_2 -> {
                if (pressed && !rtHeld) { rtHeld = true
                    InputBridge.keyFromAndroid(KeyEvent(KeyEvent.ACTION_DOWN, androidKc)) }
                else if (!pressed && rtHeld) { rtHeld = false
                    InputBridge.keyFromAndroid(KeyEvent(KeyEvent.ACTION_UP, androidKc)) }
            }
            else -> {}
        }
    }

    private fun Int.toAndroidKeycode(): Int? = when (this) {
        VkTranslator.Vk.KEY_W -> KeyEvent.KEYCODE_W
        VkTranslator.Vk.KEY_A -> KeyEvent.KEYCODE_A
        VkTranslator.Vk.KEY_S -> KeyEvent.KEYCODE_S
        VkTranslator.Vk.KEY_D -> KeyEvent.KEYCODE_D
        VkTranslator.Vk.KEY_B -> KeyEvent.KEYCODE_B
        VkTranslator.Vk.KEY_H -> KeyEvent.KEYCODE_H
        VkTranslator.Vk.KEY_Q -> KeyEvent.KEYCODE_Q
        VkTranslator.Vk.KEY_E -> KeyEvent.KEYCODE_E
        VkTranslator.Vk.KEY_1 -> KeyEvent.KEYCODE_1
        VkTranslator.Vk.KEY_2 -> KeyEvent.KEYCODE_2
        VkTranslator.Vk.SPACE -> KeyEvent.KEYCODE_SPACE
        VkTranslator.Vk.ESCAPE -> KeyEvent.KEYCODE_ESCAPE
        VkTranslator.Vk.KEY_P -> KeyEvent.KEYCODE_P
        VkTranslator.Vk.F1 -> KeyEvent.KEYCODE_F1
        VkTranslator.Vk.F2 -> KeyEvent.KEYCODE_F2
        else -> null
    }

    companion object {
        private const val TAG = "GamepadMapper"
        private const val DEAD_ZONE = 0.10f
    }
}
