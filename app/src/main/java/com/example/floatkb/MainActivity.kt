package com.example.floatkb

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var shizukuStatus: TextView

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        this.text = label
        setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(TextView(this).apply {
            text = "FLOAT"
            textSize = 30f
            setTextColor(0xFFFF3131.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Floating keyboard"
            textSize = 15f
            setPadding(0, 0, 0, (14 * d).toInt())
        })

        status = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (10 * d).toInt())
        }
        col.addView(status)

        col.addView(button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("Show / hide keyboard") {
            val svc = KeyboardService.instance
            if (svc == null) {
                Toast.makeText(this, "Enable FLOAT in Accessibility first", Toast.LENGTH_LONG).show()
            } else {
                svc.toggle()
            }
        })

        shizukuStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, (18 * d).toInt(), 0, (6 * d).toInt())
        }
        col.addView(shizukuStatus)

        col.addView(button("1. Allow Shizuku") {
            ShizukuKeys.requestPermission()
            status.postDelayed({ refresh() }, 1500)
        })
        col.addView(button("2. Connect key service") {
            ShizukuKeys.connect(this)
            status.postDelayed({ refresh() }, 1000)
        })
        col.addView(button("Refresh status") { refresh() })

        col.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, (16 * d).toInt(), 0, (6 * d).toInt())
            text = "Test: show the keyboard, switch it to PC mode, tap the box below, " +
                "then press keys on the floating keyboard."
        })
        col.addView(EditText(this).apply { hint = "Test field" })

        col.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, (16 * d).toInt(), 0, 0)
            text = "How to use\n" +
                "Normal mode: types into the focused text field, or into the pad at the top " +
                "when no field is active (Copy to paste elsewhere).\n\n" +
                "PC mode: sends real key presses to any app (needs Shizuku running, " +
                "and the two buttons above done)."
        })

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = KeyboardService.instance != null
        status.text = if (on) "● Ready: Accessibility is ON" else "○ Accessibility is OFF. Enable FLOAT below."
        status.setTextColor(if (on) 0xFF1B8A3B.toInt() else 0xFFC62828.toInt())

        val running = ShizukuKeys.isRunning()
        val allowed = ShizukuKeys.hasPermission()
        val connected = ShizukuKeys.isConnected()
        shizukuStatus.text = "PC key-press mode (Shizuku)\n" +
            (if (running) "● Shizuku is running" else "○ Shizuku is not running") + "\n" +
            (if (allowed) "● FLOAT is allowed" else "○ FLOAT is not allowed yet") + "\n" +
            (if (connected) "● Key service connected" else "○ Key service not connected")
    }
}
