package com.strongholddroid.emulator.controls

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.strongholddroid.emulator.input.InputBridge
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import com.strongholddroid.emulator.input.VkTranslator

/**
 * Multi-touch virtual mouse for the RTS overlay.
 *
 * This is NOT a touchpad-style relative pointer. It is a "floating cursor"
 * — the cursor lives in absolute screen coordinates and follows the
 * primary touch. Taps produce mouse-button events; drags produce a left
 * mouse-button drag (which is how Stronghold Crusader implements area-
 * select rubber-band). The user can enable "drag lock" so a short tap
 * with a drag continued afterwards is interpreted as a held-LMB drag for
 * unit selection.
 *
 * Why absolute instead of relative?
 *   SC's mouse cursor is *part of the game UI* — menus, build menu tiles,
 *   minimap clicks all need to land where the cursor visibly is. A
 *   touchpad-style relative pointer would force the user to swipe to
 *   position the cursor, which is jarring when the cursor itself is the
 *   primary feedback. Absolute lets us map touch → cursor 1:1.
 *
 * Anti-jitter
 * -----------
 * Touch input is noisy; even a "still" finger fires ~5 motion events/s
 * with 0.5-2 px of jitter. We apply [mouseSmoothing] EMA so the cursor
 * glides to the target rather than vibrating. SC's mouse coordinate
 * rounding is fine-grained enough to absorb this without flicker.
 *
 * Edge-scroll
 * -----------
 * When the smoothed cursor enters the [ControlProfile.edgeScrollPx]-px
 * border band, we periodically fire mouse_wheel-like camera scrolls at
 * a rate of [edgeScrollSpeed] px/s until the cursor leaves the band.
 * Disabled during an active drag (so area-select near the screen edge
 * doesn't drift the camera).
 */
class VirtualMouse(
    context: Context,
    private val profile: ControlProfile,
    private val surfaceWidth: () -> Int,
    private val surfaceHeight: () -> Int,
) : View(context) {

    private val tag = "VirtualMouse"

    // Smoothed cursor position in normalized 0..1 coordinates.
    private var curX = 0.5f
    private var curY = 0.5f

    // Active pointer info — we only honor one primary pointer at a time
    // (the others are reserved for two-finger gestures, see [GestureHandler]).
    private var primaryPointerId = MotionEvent.INVALID_POINTER_ID
    private var lastTouchDownMs = 0L
    private var lastTapUpMs = 0L
    private var touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Drag state
    private var lmbHeld = false
    private var dragStarted = false
    private var dragLockUntilMs = 0L
    private var dragLastX = 0f
    private var dragLastY = 0f

    // Edge scroll
    private var edgeScrollJob: Thread? = null
    private val edgeScrollStop = java.util.concurrent.atomic.AtomicBoolean(false)

    // Paint for the cursor dot
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    fun updateProfile(p: ControlProfile) {
        // The caller must rebuild us if structural changes happen — but
        // simple tunables can be hot-swapped.
    }

    /** Returns the current normalized cursor position. */
    fun cursorPos(): Pair<Float, Float> = curX to curY

    /**
     * Drives the cursor from an arbitrary source — used by [GamepadMapper]
     * when the left stick is bound to "VIRTUAL_MOUSE". The deltas are
     * already pre-multiplied by the controller's dead-zone and gain.
     */
    fun driveFromGamepad(dX: Float, dY: Float) {
        if (lmbHeld || dragStarted) return  // user is actively dragging
        val sens = profile.mouseSensitivity
        val newX = (curX + dX * 0.0025f * sens).coerceIn(0f, 1f)
        val newY = (curY + dY * 0.0025f * sens).coerceIn(0f, 1f)
        commitPosition(newX, newY, sendMove = true)
        maybeEdgeScroll()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!profile.showHud) return
        val px = curX * width
        val py = curY * height
        canvas.drawCircle(px, py, 8f, dotPaint)
        canvas.drawCircle(px, py, 14f, ringPaint)
        if (lmbHeld) {
            canvas.drawCircle(px, py, 18f, ringPaint.apply { alpha = 200 })
        }
    }

    // ---------------------------------------------------------------
    // Public interface — called by [RtsControlOverlay] when this view
    // owns a given MotionEvent. The overlay decides *which* events go to
    // the mouse vs. the [GestureHandler] based on pointer count.
    // ---------------------------------------------------------------

    fun onSinglePointerEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_UP   -> onUp(e)
            MotionEvent.ACTION_CANCEL -> onCancel(e)
        }
        return true
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    private fun onDown(e: MotionEvent) {
        primaryPointerId = e.getPointerId(0)
        lastTouchDownMs = SystemClock.uptimeMillis()
        val nx = e.x / width
        val ny = e.y / height
        commitPosition(nx, ny, sendMove = true)

        // Double-tap detection — used to detect "double click to fire
        // a context action" like SC's "double-click resource to claim".
        if (lastTapUpMs != 0L &&
            lastTouchDownMs - lastTapUpMs < profile.mouseButtonStickyDoubleClickTimeMs.toLong() * 2) {
            // Two taps → fire a left double-click on the down of the second
            InputBridge.mouseButton(0, down = true, buttonMask = InputBridge.BTN_LEFT, xNorm = curX, yNorm = curY)
            InputBridge.mouseButton(0, down = false, buttonMask = InputBridge.BTN_LEFT, xNorm = curX, yNorm = curY)
            InputBridge.mouseButton(0, down = true, buttonMask = InputBridge.BTN_LEFT, xNorm = curX, yNorm = curY)
            InputBridge.mouseButton(0, down = false, buttonMask = InputBridge.BTN_LEFT, xNorm = curX, yNorm = curY)
            lmbHeld = false
            dragStarted = false
            return
        }

        // Single tap starts a "potential" LMB-down; we don't fire mouse_down
        // immediately if drag-lock is enabled — we wait a few ms to see if
        // the user moves past touchSlop before deciding.
        if (profile.dragLockEnabled) {
            dragLockUntilMs = lastTouchDownMs + profile.dragLockTimeoutMs
            lmbHeld = false  // provisional
        } else {
            lmbHeld = true
            InputBridge.mouseButton(0, down = true, buttonMask = InputBridge.BTN_LEFT, xNorm = curX, yNorm = curY)
        }
        dragLastX = e.x
        dragLastY = e.y
        dragStarted = false
    }

    private fun onMove(e: MotionEvent) {
        if (primaryPointerId == MotionEvent.INVALID_POINTER_ID) return
        val idx = e.findPointerIndex(primaryPointerId)
        if (idx < 0) return
        val x = e.getX(idx)
        val y = e.getY(idx)

        // Apply smoothing
        val alpha = (1.0f - profile.mouseSmoothing).coerceIn(0.05f, 1.0f)
        val rawX = x / width
        val rawY = y / height
        val newX = curX + (rawX - curX) * alpha
        val newY = curY + (rawY - curY) * alpha
        commitPosition(newX, newY, sendMove = true)

        // Detect drag start
        if (!dragStarted) {
            val dx = x - dragLastX
            val dy = y - dragLastY
            if (hypot(dx, dy) > touchSlop) {
                dragStarted = true
                if (profile.dragLockEnabled && !lmbHeld) {
                    // Promote provisional tap → real LMB-down + a synthetic
                    // move to the start of the drag.
                    lmbHeld = true
                    InputBridge.mouseButton(0, down = true, buttonMask = InputBridge.BTN_LEFT,
                        xNorm = dragLastX / width, yNorm = dragLastY / height)
                    InputBridge.mouseMove(0, dragLastX / width, dragLastY / height)
                }
            }
        }

        if (lmbHeld) {
            maybeEdgeScroll(forceOff = true)  // no edge-scroll while dragging
        } else {
            maybeEdgeScroll()
        }
        invalidate()
    }

    private fun onUp(e: MotionEvent) {
        val tapDuration = SystemClock.uptimeMillis() - lastTouchDownMs
        if (lmbHeld) {
            InputBridge.mouseButton(0, down = false, buttonMask = InputBridge.BTN_LEFT, xNorm = curX, yNorm = curY)
        } else if (!dragStarted && tapDuration < profile.dragLockTimeoutMs) {
            // It was a quick tap (no drag, dragLock never promoted)
            InputBridge.mouseButton(0, down = true,  buttonMask = InputBridge.BTN_LEFT, xNorm = curX, yNorm = curY)
            InputBridge.mouseButton(0, down = false, buttonMask = InputBridge.BTN_LEFT, xNorm = curX, yNorm = curY)
        }
        lmbHeld = false
        dragStarted = false
        dragLockUntilMs = 0
        primaryPointerId = MotionEvent.INVALID_POINTER_ID
        lastTapUpMs = SystemClock.uptimeMillis()
        stopEdgeScroll()
        invalidate()
    }

    private fun onCancel(e: MotionEvent) {
        if (lmbHeld) {
            InputBridge.mouseButton(0, down = false, buttonMask = InputBridge.BTN_LEFT, xNorm = curX, yNorm = curY)
        }
        lmbHeld = false
        dragStarted = false
        primaryPointerId = MotionEvent.INVALID_POINTER_ID
        stopEdgeScroll()
        invalidate()
    }

    private fun commitPosition(newX: Float, newY: Float, sendMove: Boolean) {
        var x = newX
        var y = newY
        if (profile.mouseInvertX) x = 1f - x
        if (profile.mouseInvertY) y = 1f - y
        curX = x.coerceIn(0f, 1f)
        curY = y.coerceIn(0f, 1f)
        if (sendMove) {
            InputBridge.mouseMove(0, curX, curY)
        }
    }

    private fun maybeEdgeScroll(forceOff: Boolean = false) {
        if (!profile.edgeScrollEnabled || forceOff) {
            stopEdgeScroll()
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val px = curX * w
        val py = curY * h
        val band = profile.edgeScrollPx.toFloat()
        val (left, right)   = (px < band) to (px > w - band)
        val (top, bottom)   = (py < band) to (py > h - band)

        if (!(left || right || top || bottom)) {
            stopEdgeScroll()
            return
        }

        // (Re)start the scroll thread
        if (edgeScrollJob?.isAlive == true) return
        edgeScrollStop.set(false)
        edgeScrollJob = Thread {
            val intervalMs = 16L
            val speed = profile.edgeScrollSpeed  // px/s
            while (!edgeScrollStop.get()) {
                val cx = curX * width
                val cy = curY * height
                val dx = when {
                    cx < band -> -speed * intervalMs / 1000f
                    cx > width - band -> speed * intervalMs / 1000f
                    else -> 0f
                }
                val dy = when {
                    cy < band -> -speed * intervalMs / 1000f
                    cy > height - band -> speed * intervalMs / 1000f
                    else -> 0f
                }
                if (dx != 0f || dy != 0f) {
                    // Push a synthetic camera-scroll key event — SC scrolls
                    // the camera when the cursor is at the edge of the screen,
                    // so we don't strictly need to send anything. But many
                    // users disable in-game edge scroll; we send arrow-key
                    // presses to make the camera move regardless.
                    // Send arrows if outside the in-game edge scroll.
                    post { applyArrowScroll(dx, dy) }
                }
                Thread.sleep(intervalMs)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun applyArrowScroll(dxPx: Float, dyPx: Float) {
        // Approximation: 4 arrow key taps / sec at speed 600 px/s
        val rate = 4
        val now = SystemClock.uptimeMillis()
        if (now - lastArrowSentMs < 1000L / rate) return
        lastArrowSentMs = now
        if (dxPx < 0) sendArrow(VkTranslator.Vk.LEFT)
        if (dxPx > 0) sendArrow(VkTranslator.Vk.RIGHT)
        if (dyPx < 0) sendArrow(VkTranslator.Vk.UP)
        if (dyPx > 0) sendArrow(VkTranslator.Vk.DOWN)
    }

    private var lastArrowSentMs = 0L

    private fun sendArrow(vk: Int) {
        // Reuse InputBridge via a small reflective indirection — actually
        // InputBridge doesn't yet expose a "sendVk" method directly. For
        // the prototype we just enqueue key events using the public API:
        val e = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_DOWN, vk.toKeypadCode())
        InputBridge.keyFromAndroid(e)
        val up = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_UP, vk.toKeypadCode())
        InputBridge.keyFromAndroid(up)
    }

    private fun stopEdgeScroll() {
        edgeScrollStop.set(true)
        edgeScrollJob = null
    }

    private fun Boolean.to(other: Boolean): Pair<Boolean, Boolean> = this to other

    private fun Int.toKeypadCode(): Int = when (this) {
        com.strongholddroid.emulator.input.VkTranslator.Vk.LEFT  -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
        com.strongholddroid.emulator.input.VkTranslator.Vk.RIGHT -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        com.strongholddroid.emulator.input.VkTranslator.Vk.UP    -> android.view.KeyEvent.KEYCODE_DPAD_UP
        com.strongholddroid.emulator.input.VkTranslator.Vk.DOWN   -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
        else -> 0
    }
}
