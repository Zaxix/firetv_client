package com.auroratv.desktop

import com.auroratv.core.Log
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.KeyboardFocusManager
import java.awt.Robot
import java.awt.event.KeyEvent
import kotlin.concurrent.thread

/**
 * Xbox-compatible controller support on Windows via XInput. Controller input is translated into the
 * same keys the UI already understands (arrows, Enter, Escape, Space), so every screen gets controller
 * navigation for free. Only active while an Aurora window has focus.
 */
class GamepadBridge {
    @Suppress("FunctionName")
    private interface XInput : Library {
        fun XInputGetState(userIndex: Int, state: Pointer): Int
    }

    private val xinput: XInput? = if (!isWindows) null else
        listOf("xinput1_4", "xinput1_3", "xinput9_1_0").firstNotNullOfOrNull { name -> runCatching { Native.load(name, XInput::class.java) }.getOrNull() }

    @Volatile private var running = false
    private val robot by lazy { runCatching { Robot() }.getOrNull() }

    private val mapping = listOf(
        0x0001 to KeyEvent.VK_UP, 0x0002 to KeyEvent.VK_DOWN, 0x0004 to KeyEvent.VK_LEFT, 0x0008 to KeyEvent.VK_RIGHT,
        0x1000 to KeyEvent.VK_ENTER, // A
        0x2000 to KeyEvent.VK_ESCAPE, // B
        0x4000 to KeyEvent.VK_SPACE, // X: play/pause
        0x0010 to KeyEvent.VK_SPACE, // Start
        0x0020 to KeyEvent.VK_ESCAPE, // Back/View
    )

    fun start() {
        val x = xinput ?: return
        if (running) return
        running = true
        thread(name = "gamepad", isDaemon = true) {
            val state = Memory(16)
            var previous = 0
            val heldSince = mutableMapOf<Int, Long>()
            val lastRepeat = mutableMapOf<Int, Long>()
            Log.i("Gamepad", "XInput controller support active")
            while (running) {
                Thread.sleep(16)
                val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null
                var buttons = 0
                for (i in 0 until 4) {
                    if (x.XInputGetState(i, state) == 0) {
                        buttons = buttons or (state.getShort(4).toInt() and 0xffff)
                        // Left stick acts as a D-pad (deadzone ~50%).
                        val lx = state.getShort(8).toInt(); val ly = state.getShort(10).toInt()
                        if (lx < -16000) buttons = buttons or 0x0004
                        if (lx > 16000) buttons = buttons or 0x0008
                        if (ly > 16000) buttons = buttons or 0x0001
                        if (ly < -16000) buttons = buttons or 0x0002
                        break
                    }
                }
                if (!focused) { previous = buttons; continue }
                val now = System.currentTimeMillis()
                for ((mask, key) in mapping) {
                    val down = buttons and mask != 0
                    val wasDown = previous and mask != 0
                    when {
                        down && !wasDown -> { press(key); heldSince[mask] = now; lastRepeat[mask] = now }
                        down && wasDown && key in REPEATABLE -> {
                            // Hold to repeat: after 400 ms, every 110 ms (fast scrolling and seeking).
                            if (now - (heldSince[mask] ?: now) > 400 && now - (lastRepeat[mask] ?: now) > 110) { press(key); lastRepeat[mask] = now }
                        }
                        !down && wasDown -> { heldSince.remove(mask); lastRepeat.remove(mask) }
                    }
                }
                previous = buttons
            }
        }
    }

    private fun press(key: Int) {
        val r = robot ?: return
        r.keyPress(key)
        r.keyRelease(key)
    }

    fun stop() { running = false }

    private companion object {
        val REPEATABLE = setOf(KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT)
    }
}
