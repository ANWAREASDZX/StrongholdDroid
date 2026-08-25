package com.strongholddroid.emulator.controls

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.strongholddroid.emulator.R
import com.strongholddroid.emulator.input.InputBridge
import com.strongholddroid.emulator.input.VkTranslator

/**
 * The full RTS control overlay — owns a [VirtualMouse], a [GestureHandler],
 * a [GamepadMapper], and a small set of on-screen "command buttons" that
 * cover the SC hotkeys most-needed on a touch device (B for build menu,
 * M for mercenary post, Esc for menu, Space for pause, etc.).
 *
 * Layout (landscape, 16:9 example):
 *
 *   ┌──────────────────────────────────────────────────────┐
 *   │  [B] [M] [H]    (top-left action ring)              │
 *   │                                                      │
 *   │              ●  ← single-finger cursor dot           │
 *   │                                                      │
 *   │                          (free area for gestures)    │
 *   │                                                      │
 *   │   [pause]      [keyboard]      [saves]    [esc]     │
 *   └──────────────────────────────────────────────────────┘
 *
 * The overlay lives as a transparent [FrameLayout] that wraps the
 * SurfaceView where Wine renders. All on-screen buttons are invisible
 * to Wine (they sit above the surface, so the touch events that hit
 * them are *not* forwarded to the game).
 *
 * Touch event routing:
 *   • Pointer 0 only        → [VirtualMouse] (single-finger cursor)
 *   • Pointer 0 + extra ptrs → [GestureHandler] (multi-touch)
 *   • Pointer on a button   → button handles it, never reaches mouse/gesture
 *
 * Gamepad events come through [GamepadMapper.dispatchGenericMotionEvent]
 * and are translated to virtual mouse/keys via [InputBridge].
 */
class RtsControlOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private lateinit var profile: ControlProfile
    private lateinit var mouse: VirtualMouse
    private lateinit var gesture: GestureHandler
    private lateinit var gamepad: GamepadMapper

    private val actionButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    fun initialize(profile: ControlProfile, surfaceW: () -> Int, surfaceH: () -> Int) {
        this.profile = profile
        mouse = VirtualMouse(context, profile, surfaceW, surfaceH).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        gesture = GestureHandler(context, profile,
            onWheel = { dy -> InputBridge.mouseWheel(0, mouse.cursorPos().first,
                mouse.cursorPos().second, dy) },
            onPan = { _, _ -> /* forward as arrow keys — done in [VirtualMouse.onMove] */ },
            onRotate = { rad -> rotateCameraBy(rad) },
            onRightClick = { mouseRightClickAtCursor() },
            onMiddleClick = { mouseMiddleClickAtCursor() },
            onEscape = { sendVkDownUp(VkTranslator.Vk.ESCAPE) },
        )
        gamepad = GamepadMapper(context, profile).also { it.attach(this) }

        addView(mouse)
        addActionButtons()
        if (profile.showKeyboardButton) addKeyboardButton()
    }

    // ----- Top-level event dispatch -----

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Forward every event to the gesture handler — if it has multi-
        // pointer content, it consumes; otherwise the mouse handles the
        // primary pointer.
        if (ev.pointerCount >= 2) {
            gesture.onMultiPointerEvent(ev)
            // Mouse: cancel its current drag (we are now in a gesture)
            mouse.onSinglePointerEvent(MotionEvent.obtainNoHistory(ev).apply {
                action = MotionEvent.ACTION_CANCEL
            })
            return true
        }
        // 1 pointer — drop down to the mouse
        return mouse.onSinglePointerEvent(ev)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        gamepad.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (gamepad.onKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (gamepad.onKeyUp(keyCode, event)) return true
        return super.onKeyUp(keyCode, event)
    }

    // ----- On-screen action buttons -----

    private fun addActionButtons() {
        val ring = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = 24; leftMargin = 24
            }
            setBackgroundColor(Color.TRANSPARENT)
        }
        ring.addView(actionButton("B", "Build") {
            sendVkDownUp(VkTranslator.Vk.KEY_B)
        })
        ring.addView(actionButton("M", "Mercenaries") {
            sendVkDownUp(VkTranslator.Vk.KEY_M)
        })
        ring.addView(actionButton("H", "Lord info") {
            sendVkDownUp(VkTranslator.Vk.KEY_H)
        })
        ring.addView(actionButton("F1", "Quick select 1") {
            sendVkDownUp(VkTranslator.Vk.F1)
        })
        ring.addView(actionButton("Esc", "Pause/menu") {
            sendVkDownUp(VkTranslator.Vk.ESCAPE)
        })
        addView(ring)
    }

    private fun actionButton(label: String, tag: String, action: () -> Unit): Button =
        Button(context).apply {
            text = label
            contentDescription = tag
            setBackgroundColor(Color.argb(150, 30, 30, 30))
            setTextColor(Color.WHITE)
            setOnClickListener { action() }
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8 }
        }

    private fun addKeyboardButton() {
        val btn = Button(context).apply {
            text = "⌨"
            contentDescription = "Show virtual keyboard"
            setBackgroundColor(Color.argb(150, 30, 30, 30))
            setTextColor(Color.WHITE)
            setOnClickListener { showKeyboard() }
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { bottomMargin = 24; rightMargin = 24 }
        }
        addView(btn)
    }

    // ----- Mouse button helpers used by [GestureHandler] -----

    private fun mouseRightClickAtCursor() {
        val (x, y) = mouse.cursorPos()
        InputBridge.mouseButton(0, down = true,  buttonMask = InputBridge.BTN_RIGHT, xNorm = x, yNorm = y)
        InputBridge.mouseButton(0, down = false, buttonMask = InputBridge.BTN_RIGHT, xNorm = x, yNorm = y)
    }

    private fun mouseMiddleClickAtCursor() {
        val (x, y) = mouse.cursorPos()
        InputBridge.mouseButton(0, down = true,  buttonMask = InputBridge.BTN_MIDDLE, xNorm = x, yNorm = y)
        InputBridge.mouseButton(0, down = false, buttonMask = InputBridge.BTN_MIDDLE, xNorm = x, yNorm = y)
    }

    private fun rotateCameraBy(rad: Float) {
        // SC's Q/E rotate the camera. ~1 keypress per ~30° of twist.
        val keys = (abs(rad) / (Math.PI / 6)).toInt().coerceIn(1, 3)
        val vk = if (rad > 0) VkTranslator.Vk.KEY_E else VkTranslator.Vk.KEY_Q
        repeat(keys) { sendVkDownUp(vk) }
    }

    private fun sendVkDownUp(vk: Int) {
        InputBridge.keyFromAndroid(KeyEvent(KeyEvent.ACTION_DOWN, vk.androidCode()))
        InputBridge.keyFromAndroid(KeyEvent(KeyEvent.ACTION_UP, vk.androidCode()))
    }

    private fun Int.androidCode(): Int = when (this) {
        VkTranslator.Vk.KEY_B      -> KeyEvent.KEYCODE_B
        VkTranslator.Vk.KEY_M      -> KeyEvent.KEYCODE_M
        VkTranslator.Vk.KEY_H      -> KeyEvent.KEYCODE_H
        VkTranslator.Vk.KEY_Q      -> KeyEvent.KEYCODE_Q
        VkTranslator.Vk.KEY_E      -> KeyEvent.KEYCODE_E
        VkTranslator.Vk.ESCAPE     -> KeyEvent.KEYCODE_ESCAPE
        VkTranslator.Vk.SPACE      -> KeyEvent.KEYCODE_SPACE
        VkTranslator.Vk.F1         -> KeyEvent.KEYCODE_F1
        VkTranslator.Vk.F2         -> KeyEvent.KEYCODE_F2
        VkTranslator.Vk.LEFT       -> KeyEvent.KEYCODE_DPAD_LEFT
        VkTranslator.Vk.RIGHT      -> KeyEvent.KEYCODE_DPAD_RIGHT
        VkTranslator.Vk.UP         -> KeyEvent.KEYCODE_DPAD_UP
        VkTranslator.Vk.DOWN       -> KeyEvent.KEYCODE_DPAD_DOWN
        VkTranslator.Vk.KEY_W      -> KeyEvent.KEYCODE_W
        VkTranslator.Vk.KEY_A      -> KeyEvent.KEYCODE_A
        VkTranslator.Vk.KEY_S      -> KeyEvent.KEYCODE_S
        VkTranslator.Vk.KEY_D      -> KeyEvent.KEYCODE_D
        VkTranslator.Vk.KEY_1      -> KeyEvent.KEYCODE_1
        VkTranslator.Vk.KEY_2      -> KeyEvent.KEYCODE_2
        else -> 0
    }

    private fun showKeyboard() {
        // For the prototype we just dispatch a long Space press to open
        // SC's chat/console. A full pop-up QWERTY would be a separate
        // composable; see [VirtualKeyboard.kt].
        VirtualKeyboard(context).show(this) { txt -> /* SC has no chat input on console */ }
    }
}
