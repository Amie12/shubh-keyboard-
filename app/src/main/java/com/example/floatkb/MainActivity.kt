package com.example.floatkb

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

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
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Floating keyboard"
            textSize = 15f
            setPadding(0, 0, 0, (14 * d).toInt())
        })

        status = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (10 * d).toInt())
        }
        col.addView(status)

        col.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(Button(this).apply {
            text = "Show / hide keyboard"
            setOnClickListener {
                val svc = KeyboardService.instance
                if (svc == null) {
                    Toast.makeText(this@MainActivity, "Enable FLOAT in Accessibility first", Toast.LENGTH_LONG).show()
                } else {
                    svc.toggle()
                }
            }
        })

        col.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, (16 * d).toInt(), 0, 0)
            text = "How to use\n" +
                "1. Enable FLOAT in Accessibility (button above).\n" +
                "2. Pull down the notification shade and tap \"Show / Hide keyboard\", " +
                "or add the FLOAT tile to Quick Settings.\n" +
                "3. Tap a text field and type. Text goes into that field.\n" +
                "4. No text field? Typing goes into the pad at the top of the keyboard. " +
                "Tap Copy to copy it and paste it anywhere.\n\n" +
                "Note: games that read key presses (no text field) need the key-press mode, " +
                "which is the next update."
        })

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val on = KeyboardService.instance != null
        status.text = if (on) "● Ready: Accessibility is ON" else "○ Accessibility is OFF. Enable FLOAT below."
        status.setTextColor(if (on) 0xFF1B8A3B.toInt() else 0xFFC62828.toInt())
    }
}
