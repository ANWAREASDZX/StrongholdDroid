package com.strongholddroid.emulator.controls

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * StrongholdDroid control profile — fully rebindable by the user and the
 * Settings → Controls page persists/edits these as JSON.
 *
 * The profile is deliberately *per-game* (keyed by [GameProfile.slug])
 * because Stronghold Crusader 1.1 (no F1-F4 quick-select) and the HD
 * Extreme edition have a slightly different hotkey layout.
 *
 * Coordinate convention
 * ---------------------
 * Virtual mouse positions are stored in *normalized* 0..1 coordinates so
 * the same profile works across screen sizes. Conversion to absolute
 * Wine coords happens in [com.strongholddroid.emulator.input.InputBridge].
 */
@Parcelize
@Serializable
data class ControlProfile(
    val gameProfileSlug: String,
    val mouseSensitivity: Float = 1.0f,        // 0.1..5.0 — virtual mouse speed multiplier
    val mouseSmoothing: Float = 0.25f,         // 0..0.5 — exponential smoothing factor
    val mouseInvertX: Boolean = false,
    val mouseInvertY: Boolean = false,
    val dragLockEnabled: Boolean = true,      // LMB drag = "paintbrush" for area-select
    val dragLockTimeoutMs: Int = 800,
    val rightClickGesture: RtsGesture = RtsGesture.TWO_FINGER_TAP,  // ← SC map-context-menu
    val middleClickGesture: RtsGesture = RtsGesture.LONG_PRESS,
    val zoomGesture: RtsGesture = RtsGesture.PINCH,
    val panGesture: RtsGesture = RtsGesture.TWO_FINGER_DRAG,
    val rotateGesture: RtsGesture = RtsGesture.TWO_FINGER_TWIST,
    val edgeScrollEnabled: Boolean = true,   // cursor at screen edge → camera scrolls
    val edgeScrollPx: Int = 36,
    val edgeScrollSpeed: Float = 600f,        // px/s when triggered
    val showHud: Boolean = true,             // overlays button ring, mouse dot, etc.
    val showKeyboardButton: Boolean = true,
    val keyboardLayout: KeyboardLayout = KeyboardLayout.QWERTY,
    val mouseButtonSticky: Boolean = false,  // right click = toggle (some users prefer)
    val mouseButtonStickyDoubleClickTimeMs: Int = 250,

    /** Gamepad → SC command mapping. See [GamepadMapper]. */
    val gamepadBindings: GamepadBindings = GamepadBindings.DEFAULT,
) : Parcelable {

    /** Discrete gestures that the [GestureHandler] can recognize. */
    @Parcelize
    @Serializable
    enum class RtsGesture {
        PINCH,                // two fingers moving towards/away
        TWO_FINGER_DRAG,      // pan
        TWO_FINGER_TAP,       // both down + up within <250 ms and small travel
        TWO_FINGER_TWIST,    // rotate
        LONG_PRESS,           // single-finger held >500 ms without motion
        SWIPE,                // fast fling
        TAP,                  // single tap
        DOUBLE_TAP,           // two taps within 300 ms
        THREE_FINGER_TAP,    // rare but used for "main menu" shortcut
        NONE,                 // disable this gesture entirely
    }

    @Parcelize
    @Serializable
    enum class KeyboardLayout { QWERTY, AZERTY, QWERTZ, GAMEPAD_ONLY }

    /** Gamepad bindings for SC's most important commands. */
    @Parcelize
    @Serializable
    data class GamepadBindings(
        val leftStick:  GamepadAction = GamepadAction.VIRTUAL_MOUSE,
        val rightStick: GamepadAction = GamepadAction.CAMERA_SCROLL,
        val dpadUp:     GamepadAction = GamepadAction.VK_KEY_W,
        val dpadDown:   GamepadAction = GamepadAction.VK_KEY_S,
        val dpadLeft:   GamepadAction = GamepadAction.VK_KEY_A,
        val dpadRight:  GamepadAction = GamepadAction.VK_KEY_D,
        val buttonA:    GamepadAction = GamepadAction.MOUSE_LEFT,
        val buttonB:    GamepadAction = GamepadAction.VK_KEY_B,
        val buttonX:    GamepadAction = GamepadAction.VK_KEY_SPACE,
        val buttonY:    GamepadAction = GamepadAction.VK_KEY_H,
        val leftBumper:  GamepadAction = GamepadAction.VK_KEY_Q,
        val rightBumper: GamepadAction = GamepadAction.VK_KEY_E,
        val leftTrigger:  GamepadAction = GamepadAction.VK_KEY_1,
        val rightTrigger: GamepadAction = GamepadAction.VK_KEY_2,
        val select:     GamepadAction = GamepadAction.VK_ESCAPE,
        val start:      GamepadAction = GamepadAction.VK_PAUSE,
        val leftStickClick:  GamepadAction = GamepadAction.VK_KEY_F1,
        val rightStickClick: GamepadAction = GamepadAction.VK_KEY_F2,
    ) {
        companion object {
            val DEFAULT = GamepadBindings()
        }
    }

    /** What a single gamepad control is mapped to. */
    @Parcelize
    @Serializable
    sealed class GamepadAction : Parcelable {
        @Parcelize data object NONE : GamepadAction()
        @Parcelize data object VIRTUAL_MOUSE : GamepadAction()
        @Parcelize data object CAMERA_SCROLL : GamepadAction()
        @Parcelize data object MOUSE_LEFT : GamepadAction()
        @Parcelize data object MOUSE_RIGHT : GamepadAction()
        @Parcelize data object MOUSE_MIDDLE : GamepadAction()
        @Parcelize data object MOUSE_WHEEL_UP : GamepadAction()
        @Parcelize data object MOUSE_WHEEL_DOWN : GamepadAction()
        @Parcelize data class VK_KEY(val vk: Int) : GamepadAction()

        // Common shortcuts — avoid allocating the data class per profile.
        val VK_KEY_W = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.KEY_W)
        val VK_KEY_A = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.KEY_A)
        val VK_KEY_S = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.KEY_S)
        val VK_KEY_D = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.KEY_D)
        val VK_KEY_B = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.KEY_B)
        val VK_KEY_H = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.KEY_H)
        val VK_KEY_Q = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.KEY_Q)
        val VK_KEY_E = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.KEY_E)
        val VK_KEY_1 = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.KEY_1)
        val VK_KEY_2 = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.KEY_2)
        val VK_KEY_SPACE = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.SPACE)
        val VK_ESCAPE    = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.ESCAPE)
        val VK_PAUSE     = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.KEY_P)
        val VK_KEY_F1    = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.F1)
        val VK_KEY_F2    = VK_KEY(com.strongholddroid.emulator.input.VkTranslator.Vk.F2)
    }
}

/** Companion factory: well-known defaults shipped with the APK. */
object ControlProfiles {
    fun defaultFor(slug: String): ControlProfile = ControlProfile(
        gameProfileSlug = slug,
        mouseSensitivity = 1.0f,
        showHud = true,
        showKeyboardButton = true,
        edgeScrollEnabled = true,
    )

    fun minFor(slug: String): ControlProfile = defaultFor(slug).copy(
        mouseSensitivity = 0.7f,
        showHud = false,
        edgeScrollEnabled = false,
        showKeyboardButton = false,
    )
}
