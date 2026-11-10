package com.example.floatkb

import android.os.Binder
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import java.lang.reflect.Method

/** Runs inside the Shizuku (shell) process. Receives key events and injects them. */
class KeyServer : Binder() {

    private var manager: Any? = null
    private var injectMethod: Method? = null

    private fun prepare() {
        if (injectMethod != null) return
        val cls = try {
            Class.forName("android.hardware.input.InputManagerGlobal")
        } catch (t: Throwable) {
            Class.forName("android.hardware.input.InputManager")
        }
        val m = cls.getDeclaredMethod("getInstance").invoke(null)
        manager = m
        injectMethod = m!!.javaClass.getMethod(
            "injectInputEvent", InputEvent::class.java, Integer.TYPE
        )
    }

    private fun send(keyCode: Int, action: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        val ev = KeyEvent(now, now, action, keyCode, 0, meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        try {
            prepare()
            injectMethod!!.invoke(manager, ev, 0)
        } catch (t: Throwable) {
            // Fallback: full key press through the shell "input" tool
            if (action == KeyEvent.ACTION_DOWN) {
                try {
                    Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
                } catch (e: Throwable) {
                }
            }
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            1 -> {
                val keyCode = data.readInt()
                val action = data.readInt()
                val meta = data.readInt()
                send(keyCode, action, meta)
                reply?.writeNoException()
                true
            }
            16777114 -> {
                System.exit(0)
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }
}
