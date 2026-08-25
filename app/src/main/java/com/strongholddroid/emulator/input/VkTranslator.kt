package com.strongholddroid.emulator.input

import android.view.KeyEvent

/**
 * Maps Android [KeyEvent] keycodes to Windows Virtual-Key (VK_*) codes.
 *
 * Stronghold Crusader uses the following keybinds heavily:
 *   W A S D       — camera scroll
 *   Q E           — rotate camera
 *   Space         — pause game / close menus
 *   B             — build menu
 *   M             — mercenary post
 *   H             — lord info
 *   Ctrl+S        — save
 *   Ctrl+L        — load
 *   F1-F4         — group select / mission selection
 *   Pause         — full pause
 *   Enter         — dismiss event
 *   1..9          — quick troop type select
 *
 * These bindings live in [StrongholdCrusaderProfile] so they can be
 * remapped per-version. [VkTranslator] only handles the *physical*
 * Android→Windows code conversion.
 */
object VkTranslator {

    /** Windows VK_* codes that Stronghold cares about. */
    object Vk {
        const val BACK      = 0x08   // VK_BACK
        const val TAB       = 0x09
        const val RETURN    = 0x0D
        const val SHIFT     = 0x10
        const val CONTROL   = 0x11
        const val MENU      = 0x12   // Alt
        const val CAPITAL   = 0x14   // Caps Lock
        const val ESCAPE    = 0x1B
        const val SPACE     = 0x20
        const val PRIOR     = 0x21   // Page Up
        const val NEXT      = 0x22   // Page Down
        const val END       = 0x23
        const val HOME      = 0x24
        const val LEFT      = 0x25
        const val UP        = 0x26
        const val RIGHT      = 0x27
        const val DOWN      = 0x28
        const val INSERT   = 0x2D
        const val DELETE    = 0x2E
        const val KEY_0 = 0x30; const val KEY_1 = 0x31; const val KEY_2 = 0x32
        const val KEY_3 = 0x33; const val KEY_4 = 0x34; const val KEY_5 = 0x35
        const val KEY_6 = 0x36; const val KEY_7 = 0x37; const val KEY_8 = 0x38
        const val KEY_9 = 0x39
        const val KEY_A = 0x41; const val KEY_B = 0x42; const val KEY_C = 0x43
        const val KEY_D = 0x44; const val KEY_E = 0x45; const val KEY_F = 0x46
        const val KEY_G = 0x47; const val KEY_H = 0x48; const val KEY_I = 0x49
        const val KEY_J = 0x4A; const val KEY_K = 0x4B; const val KEY_L = 0x4C
        const val KEY_M = 0x4D; const val KEY_N = 0x4E; const val KEY_O = 0x4F
        const val KEY_P = 0x50; const val KEY_Q = 0x51; const val KEY_R = 0x52
        const val KEY_S = 0x53; const val KEY_T = 0x54; const val KEY_U = 0x55
        const val KEY_V = 0x56; const val KEY_W = 0x57; const val KEY_X = 0x58
        const val KEY_Y = 0x59; const val KEY_Z = 0x5A
        const val F1 = 0x70; const val F2 = 0x71; const val F3 = 0x72; const val F4 = 0x73
        const val F5 = 0x74; const val F5_KEY = 0x74; const val F6 = 0x75
        const val F7 = 0x76; const val F8 = 0x77; const val F9 = 0x78; const val F10 = 0x79
        const val F11 = 0x7A; const val F12 = 0x7B
        const val NUMLOCK   = 0x90
        const val SCROLL    = 0x91
        const val LSHIFT    = 0xA0
        const val RSHIFT    = 0xA1
        const val LCONTROL  = 0xA2
        const val RCONTROL  = 0xA3
        const val LMENU     = 0xA4
        const val RMENU     = 0xA5
    }

    fun androidToVk(event: KeyEvent): Int = androidToVk(event.keyCode, event.isShiftPressed)

    fun androidToVk(androidCode: Int, shifted: Boolean = false): Int = when (androidCode) {
        KeyEvent.KEYCODE_DEL          -> Vk.BACK
        KeyEvent.KEYCODE_TAB          -> Vk.TAB
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> Vk.RETURN
        KeyEvent.KEYCODE_SHIFT_LEFT   -> Vk.LSHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT  -> Vk.RSHIFT
        KeyEvent.KEYCODE_CTRL_LEFT    -> Vk.LCONTROL
        KeyEvent.KEYCODE_CTRL_RIGHT   -> Vk.RCONTROL
        KeyEvent.KEYCODE_ALT_LEFT     -> Vk.LMENU
        KeyEvent.KEYCODE_ALT_RIGHT    -> Vk.RMENU
        KeyEvent.KEYCODE_CAPS_LOCK    -> Vk.CAPITAL
        KeyEvent.KEYCODE_ESCAPE       -> Vk.ESCAPE
        KeyEvent.KEYCODE_SPACE        -> Vk.SPACE
        KeyEvent.KEYCODE_PAGE_UP      -> Vk.PRIOR
        KeyEvent.KEYCODE_PAGE_DOWN    -> Vk.NEXT
        KeyEvent.KEYCODE_MOVE_END     -> Vk.END
        KeyEvent.KEYCODE_MOVE_HOME    -> Vk.HOME
        KeyEvent.KEYCODE_DPAD_LEFT    -> Vk.LEFT
        KeyEvent.KEYCODE_DPAD_UP      -> Vk.UP
        KeyEvent.KEYCODE_DPAD_RIGHT   -> Vk.RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN    -> Vk.DOWN
        KeyEvent.KEYCODE_INSERT       -> Vk.INSERT
        KeyEvent.KEYCODE_FORWARD_DEL  -> Vk.DELETE
        KeyEvent.KEYCODE_0            -> Vk.KEY_0
        KeyEvent.KEYCODE_1            -> if (shifted) Vk.KEY_1 else Vk.KEY_1
        KeyEvent.KEYCODE_2            -> Vk.KEY_2
        KeyEvent.KEYCODE_3            -> Vk.KEY_3
        KeyEvent.KEYCODE_4            -> Vk.KEY_4
        KeyEvent.KEYCODE_5            -> Vk.KEY_5
        KeyEvent.KEYCODE_6            -> Vk.KEY_6
        KeyEvent.KEYCODE_7            -> Vk.KEY_7
        KeyEvent.KEYCODE_8            -> Vk.KEY_8
        KeyEvent.KEYCODE_9            -> Vk.KEY_9
        KeyEvent.KEYCODE_A            -> Vk.KEY_A
        KeyEvent.KEYCODE_B            -> Vk.KEY_B
        KeyEvent.KEYCODE_C            -> Vk.KEY_C
        KeyEvent.KEYCODE_D            -> Vk.KEY_D
        KeyEvent.KEYCODE_E            -> Vk.KEY_E
        KeyEvent.KEYCODE_F            -> Vk.KEY_F
        KeyEvent.KEYCODE_G            -> Vk.KEY_G
        KeyEvent.KEYCODE_H            -> Vk.KEY_H
        KeyEvent.KEYCODE_I            -> Vk.KEY_I
        KeyEvent.KEYCODE_J            -> Vk.KEY_J
        KeyEvent.KEYCODE_K            -> Vk.KEY_K
        KeyEvent.KEYCODE_L            -> Vk.KEY_L
        KeyEvent.KEYCODE_M            -> Vk.KEY_M
        KeyEvent.KEYCODE_N            -> Vk.KEY_N
        KeyEvent.KEYCODE_O            -> Vk.KEY_O
        KeyEvent.KEYCODE_P            -> Vk.KEY_P
        KeyEvent.KEYCODE_Q            -> Vk.KEY_Q
        KeyEvent.KEYCODE_R            -> Vk.KEY_R
        KeyEvent.KEYCODE_S            -> Vk.KEY_S
        KeyEvent.KEYCODE_T            -> Vk.KEY_T
        KeyEvent.KEYCODE_U            -> Vk.KEY_U
        KeyEvent.KEYCODE_V            -> Vk.KEY_V
        KeyEvent.KEYCODE_W            -> Vk.KEY_W
        KeyEvent.KEYCODE_X            -> Vk.KEY_X
        KeyEvent.KEYCODE_Y            -> Vk.KEY_Y
        KeyEvent.KEYCODE_Z            -> Vk.KEY_Z
        KeyEvent.KEYCODE_F1           -> Vk.F1
        KeyEvent.KEYCODE_F2           -> Vk.F2
        KeyEvent.KEYCODE_F3           -> Vk.F3
        KeyEvent.KEYCODE_F4           -> Vk.F4
        KeyEvent.KEYCODE_F5           -> Vk.F5
        KeyEvent.KEYCODE_F6           -> Vk.F6
        KeyEvent.KEYCODE_F7           -> Vk.F7
        KeyEvent.KEYCODE_F8           -> Vk.F8
        KeyEvent.KEYCODE_F9           -> Vk.F9
        KeyEvent.KEYCODE_F10          -> Vk.F10
        KeyEvent.KEYCODE_F11          -> Vk.F11
        KeyEvent.KEYCODE_F12          -> Vk.F12
        KeyEvent.KEYCODE_NUM_LOCK     -> Vk.NUMLOCK
        KeyEvent.KEYCODE_SCROLL_LOCK  -> Vk.SCROLL
        else -> 0
    }
}
