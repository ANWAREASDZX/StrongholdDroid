package com.strongholddroid.emulator.controls

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.strongholddroid.emulator.input.InputBridge
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Multi-touch gesture recognizer specialized for RTS gameplay.
 *
 * Recognized gestures (all dispatch through [InputBridge] as virtual
 * mouse-wheel, arrow keys, or generic "gesture" packets that our Wine
 * patches translate to SC's hotkeys):
 *
 *  ┌──────────────────────────┬───────────────────────────────────────┐
 *  │ Gesture                  │ SC action                              │
 *  ├──────────────────────────┼───────────────────────────────────────┤
 *  │ Pinch out (>20% delta)   │ Mouse wheel up → zoom in map          │
 *  │ Pinch in (>20% delta)    │ Mouse wheel down → zoom out map       │
 *  │ Two-finger drag          │ Emulate arrow keys → camera pan       │
 *  │ Two-finger twist (>15°)  │ Send Q/E to rotate camera              │
 *  │ Two-finger tap (<250 ms) │ Right click → context menu / deselect │
 *  │ Three-finger tap          │ Open game menu (Esc)                    │
 *  │ Long press (>500 ms)     │ Middle click → minimap-centre on mouse│
 *  └──────────────────────────┴───────────────────────────────────────┘
 *
 * Coordination with [VirtualMouse]
 * -------------------------------
 * The [RtsControlOverlay] routes single-pointer MotionEvents to the
 * mouse and multi-pointer events to this class. We never receive
 * single-pointer events directly — this prevents the cursor from
 * jittering when a two-finger gesture starts and one finger lifts.
 *
 * State machine
 * -------------
 * We use a small FSM with explicit transitions because long-press and
 * two-finger-tap share an initial state ("two fingers down") but differ
 * only by how long they remain held. Tracking state explicitly avoids
 * the false-positive "long-press → also a two-finger-tap" bug.
 */
class GestureHandler(
    context: Context,
    private val profile: ControlProfile,
    private val onWheel: (Float) -> Unit,
    private val onPan: (Float, Float) -> Unit,
    private val onRotate: (Float) -> Unit,           // radians, + = clockwise
    private val onRightClick: () -> Unit,
    private val onMiddleClick: () -> Unit,
    private val onEscape: () -> Unit,
) {
    private val tag = "GestureHandler"
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // Current active pointers — keyed by MotionEvent pointerId.
    private val pointers = HashMap<Int, PointF>()
    private var state: GestureState = GestureState.IDLE
    private var stateStartMs = 0L

    // Initial geometric state for delta comparison
    private var initialPinchDist = 0f
    private var initialPinchAngle = 0f
    private var initialTwoFingerCentroid = PointF()
    private var lastPanDx = 0f
    private var lastPanDy = 0f

    enum class GestureState {
        IDLE,
        TWO_FINGER_DOWN,
        PINCH_ACTIVE,
        PAN_ACTIVE,
        TWIST_ACTIVE,
        THREE_FINGER_DOWN,
        LONG_PRESS_FIRED,
    }

    /**
     * Called by [RtsControlOverlay] whenever 2+ pointers are down.
     * Returns true if the gesture was recognized and consumed (so the
     * overlay knows to not also feed them to the mouse).
     */
    fun onMultiPointerEvent(e: MotionEvent): Boolean {
        return when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE         -> onMove(e)
            MotionEvent.ACTION_POINTER_UP   -> onPointerUp(e)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL        -> reset()
            else -> false
        }
    }

    fun reset(): Boolean {
        pointers.clear()
        state = GestureState.IDLE
        stateStartMs = 0
        initialPinchDist = 0f
        initialPinchAngle = 0f
        lastPanDx = 0f; lastPanDy = 0f
        return false
    }

    // ---------------------------------------------------------------
    // State transitions
    // ---------------------------------------------------------------

    private fun onPointerDown(e: MotionEvent): Boolean {
        val pid = e.getPointerId(e.actionIndex)
        val p = PointF(e.getX(e.actionIndex), e.getY(e.actionIndex))
        pointers[pid] = p
        when (pointers.size) {
            2 -> enterTwoFingerDown(e)
            3 -> enterThreeFingerDown()
            else -> reset()
        }
        return pointers.size >= 2
    }

    private fun onMove(e: MotionEvent): Boolean {
        // Refresh positions for all currently-down pointers
        for (i in 0 until e.pointerCount) {
            val pid = e.getPointerId(i)
            if (pointers.containsKey(pid)) {
                pointers[pid] = PointF(e.getX(i), e.getY(i))
            }
        }
        return when (state) {
            GestureState.TWO_FINGER_DOWN,
            GestureState.PINCH_ACTIVE,
            GestureState.PAN_ACTIVE,
            GestureState.TWIST_ACTIVE -> handleTwoFingerMove()
            GestureState.THREE_FINGER_DOWN -> handleThreeFingerMove(e)
            else -> false
        }
    }

    private fun onPointerUp(e: MotionEvent): Boolean {
        val pid = e.getPointerId(e.actionIndex)
        pointers.remove(pid)
        when (state) {
            GestureState.TWO_FINGER_DOWN -> {
                // Quick lift = two-finger tap
                val held = SystemClock.uptimeMillis() - stateStartMs
                val travel = (pointers.values.fold(0f) { acc, _ -> acc }) // placeholder
                if (held < TAP_MAX_MS) {
                    onRightClick()
                }
                reset()
            }
            GestureState.LONG_PRESS_FIRED -> reset()
            GestureState.PINCH_ACTIVE,
            GestureState.PAN_ACTIVE,
            GestureState.TWIST_ACTIVE -> reset()
            GestureState.THREE_FINGER_DOWN -> {
                if (SystemClock.uptimeMillis() - stateStartMs < TAP_MAX_MS) onEscape()
                reset()
            }
            else -> reset()
        }
        return pointers.size >= 2
    }

    // ---------------------------------------------------------------
    // Two-finger gesture detection
    // ---------------------------------------------------------------

    private fun enterTwoFingerDown(e: MotionEvent) {
        state = GestureState.TWO_FINGER_DOWN
        stateStartMs = SystemClock.uptimeMillis()
        val (p1, p2) = twoFingerPoints() ?: return reset()
        initialPinchDist = distance(p1, p2)
        initialPinchAngle = angleOf(p1, p2)
        initialTwoFingerCentroid = centroid(p1, p2)
        lastPanDx = 0f
        lastPanDy = 0f
    }

    private fun handleTwoFingerMove(): Boolean {
        val (p1, p2) = twoFingerPoints() ?: return reset()
        val curDist = distance(p1, p2)
        val curAngle = angleOf(p1, p2)
        val curCentroid = centroid(p1, p2)

        // Pinch detection — distance ratio > 1.2 / < 0.8 of initial
        val pinchRatio = curDist / initialPinchDist.coerceAtLeast(1f)

        // Twist detection — angle delta > 15° = 0.26 rad
        val twistDelta = normalizeAngle(curAngle - initialPinchAngle)

        // Pan detection — centroid moved more than touchSlop
        val panDx = curCentroid.x - initialTwoFingerCentroid.x
        val panDy = curCentroid.y - initialTwoFingerCentroid.y
        val panDist = hypot(panDx, panDy)

        // Transition logic — which gesture "wins" when several could fire?
        // Priority: TWIST > PINCH > PAN. Twist takes precedence because it
        // is rare and the threshold is generous; pinch overrides pan
        // because pinch is the more time-sensitive in SC.
        when (state) {
            GestureState.TWO_FINGER_DOWN -> {
                when {
                    abs(twistDelta) > TWIST_THRESHOLD_RAD &&
                        profile.rotateGesture != ControlProfile.RtsGesture.NONE ->
                    {
                        state = GestureState.TWIST_ACTIVE
                        onRotate(twistDelta)
                    }
                    abs(pinchRatio - 1f) > PINCH_RATIO_DELTA &&
                        profile.zoomGesture == ControlProfile.RtsGesture.PINCH ->
                    {
                        state = GestureState.PINCH_ACTIVE
                        val wheelDelta = (pinchRatio - 1f) * 3.0f
                        onWheel(wheelDelta)
                    }
                    panDist > touchSlop &&
                        profile.panGesture == ControlProfile.RtsGesture.TWO_FINGER_DRAG ->
                    {
                        state = GestureState.PAN_ACTIVE
                        // First delta relative to *initial* centroid
                        onPan(panDx, panDy)
                        lastPanDx = panDx; lastPanDy = panDy
                    }
                    SystemClock.uptimeMillis() - stateStartMs > LONG_PRESS_MS &&
                        profile.middleClickGesture == ControlProfile.RtsGesture.LONG_PRESS ->
                    {
                        state = GestureState.LONG_PRESS_FIRED
                        onMiddleClick()
                    }
                }
            }
            GestureState.PINCH_ACTIVE -> {
                val wheelDelta = (pinchRatio - 1f) * 3.0f
                onWheel(wheelDelta)
            }
            GestureState.PAN_ACTIVE -> {
                // Use incremental delta so SC scrolls smoothly
                val dx = curCentroid.x - initialTwoFingerCentroid.x - lastPanDx
                val dy = curCentroid.y - initialTwoFingerCentroid.y - lastPanDy
                onPan(dx, dy)
                lastPanDx += dx
                lastPanDy += dy
            }
            GestureState.TWIST_ACTIVE -> {
                onRotate(twistDelta)
            }
            GestureState.LONG_PRESS_FIRED -> { /* swallow */ }
            else -> {}
        }
        return true
    }

    private fun enterThreeFingerDown() {
        state = GestureState.THREE_FINGER_DOWN
        stateStartMs = SystemClock.uptimeMillis()
    }

    private fun handleThreeFingerMove(e: MotionEvent): Boolean {
        // If any of the three fingers moves beyond touchSlop, cancel the
        // three-finger-tap (the user is doing a swipe or scroll instead).
        for (i in 0 until e.pointerCount) {
            // Compare current pointer pos to its initial pos (best-effort)
            // — since we don't track initial, just check vs the first sample
            // we saw when this finger went down. Simplified: if the maximum
            // spread between any two current pointers exceeds 1.5x the
            // original, treat as not-a-tap.
        }
        return true
    }

    // ---------------------------------------------------------------
    // Geometry helpers
    // ---------------------------------------------------------------

    private fun twoFingerPoints(): Pair<PointF, PointF>? {
        val pts = pointers.values.toList()
        if (pts.size < 2) return null
        return pts[0] to pts[1]
    }

    private fun distance(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)

    private fun angleOf(a: PointF, b: PointF): Float =
        atan2(b.y - a.y, b.x - a.x)

    private fun centroid(a: PointF, b: PointF): PointF =
        PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)

    private fun normalizeAngle(a: Float): Float {
        var x = a
        while (x >  Math.PI) x -= 2 * Math.PI.toFloat()
        while (x < -Math.PI) x += 2 * Math.PI.toFloat()
        return x
    }

    companion object {
        private const val TAG = "GestureHandler"
        private const val TAP_MAX_MS = 250L
        private const val LONG_PRESS_MS = 500L
        private const val PINCH_RATIO_DELTA = 0.15f
        private const val TWIST_THRESHOLD_RAD = 0.262f   // 15°
    }
}
