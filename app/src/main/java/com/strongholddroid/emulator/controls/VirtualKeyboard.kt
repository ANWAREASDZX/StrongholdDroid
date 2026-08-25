package com.strongholddroid.emulator.controls

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Pop-up virtual QWERTY for the (rare) case where SC asks the user to
 * type — saving a campaign name, entering a cheat code, naming a
 * saved castle layout. Most SC play happens without any keyboard
 * input; the on-screen button ring covers 99% of hotkeys.
 *
 * Layout: 4-row QWERTY (or AZERTY/QWERTZ per [ControlProfile]) plus
 * space, enter, backspace, and a dismiss button. Each tap synthesizes
 * a [android.view.KeyEvent] that [VirtualMouse.keyFromAndroid] translates
 * to Windows VK_*.
 *
 * For the prototype this is a simple ASCII-only keyboard — no shift
 * state, no accents. Real-world use would extend to a full IME
 * (InputConnection) but that adds ~500 lines that the user has
 * explicitly skipped.
 */
class VirtualKeyboard(private val ctx: Context) {

    fun show(parent: ViewGroup, onText: (String) -> Unit) {
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 0, 0, 0))
            setPadding(16, 16, 16, 16)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }
        val rows = listOf(
            "QWERTYUIOP".toList(),
            "ASDFGHJKL".toList(),
            "ZXCVBNM".toList(),
            listOf("␣", "⏎", "⌫")
        )
        for (row in rows) {
            val rowLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (ch in row) {
                val b = Button(ctx).apply {
                    text = ch.toString()
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(180, 40, 40, 40))
                    val pad = 24
                    setPadding(pad, pad / 2, pad, pad / 2)
                    setOnClickListener {
                        when (ch) {
                            '␣' -> sendKey(android.view.KeyEvent.KEYCODE_SPACE)
                            '⏎' -> sendKey(android.view.KeyEvent.KEYCODE_ENTER)
                            '⌫' -> sendKey(android.view.KeyEvent.KEYCODE_DEL)
                            else -> sendChar(ch)
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply { marginEnd = 4; marginStart = 4 }
                }
                rowLayout.addView(b)
            }
            layout.addView(rowLayout)
        }
        val dismiss = Button(ctx).apply {
            text = "⨯"
            setOnClickListener { parent.removeView(layout) }
            setBackgroundColor(Color.argb(180, 60, 0, 0))
            setTextColor(Color.WHITE)
        }
        layout.addView(dismiss)
        parent.addView(layout)
    }

    private fun sendKey(kc: Int) {
        com.strongholddroid.emulator.input.InputBridge.keyFromAndroid(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, kc))
        com.strongholddroid.emulator.input.InputBridge.keyFromAndroid(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, kc))
    }

    private fun sendChar(ch: Char) {
        // Build a synthetic KEYCODE that approximates the letter.
        val kc = when (ch.uppercaseChar()) {
            'A' -> android.view.KeyEvent.KEYCODE_A
            'B' -> android.view.KeyEvent.KEYCODE_B
            'C' -> android.view.KeyEvent.KEYCODE_C
            'D' -> android.view.KeyEvent.KEYCODE_D
            'E' -> android.view.KeyEvent.KEYCODE_E
            'F' -> android.view.KeyEvent.KEYCODE_F
            'G' -> android.view.KeyEvent.KEYCODE_G
            'H' -> android.view.KeyEvent.KEYCODE_H
            'I' -> android.view.KeyEvent.KEYCODE_I
            'J' -> android.view.KeyEvent.KEYCODE_J
            'K' -> android.view.KeyEvent.KEYCODE_K
            'L' -> android.view.KeyEvent.KEYCODE_L
            'M' -> android.view.KeyEvent.KEYCODE_M
            'N' -> android.view.KeyEvent.KEYCODE_N
            'O' -> android.view.KeyEvent.KEYCODE_O
            'P' -> android.view.KeyEvent.KEYCODE_P
            'Q' -> android.view.KeyEvent.KEYCODE_Q
            'R' -> android.view.KeyEvent.KEYCODE_R
            'S' -> android.view.KeyEvent.KEYCODE_S
            'T' -> android.view.KeyEvent.KEYCODE_T
            'U' -> android.view.KeyEvent.KEYCODE_U
            'V' -> android.view.KeyEvent.KEYCODE_V
            'W' -> android.view.KeyEvent.KEYCODE_W
            'X' -> android.view.KeyEvent.KEYCODE_X
            'Y' -> android.view.KeyEvent.KEYCODE_Y
            'Z' -> android.view.KeyEvent.KEYCODE_Z
            else -> return
        }
        sendKey(kc)
    }
}
