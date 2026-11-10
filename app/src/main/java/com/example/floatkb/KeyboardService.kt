package com.example.floatkb

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class KeyboardService : AccessibilityService() {

    companion object {
        var instance: KeyboardService? = null
        private const val ACTION_TOGGLE = "com.example.floatkb.TOGGLE"
        private const val CHANNEL_ID = "float_kb"
    }

    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private var keysBox: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    val isShowing get() = root != null

    private var shift = false
    private var symbols = false

    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = toggle()
    }

    // ---------- lifecycle ----------
    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ContextCompat.registerReceiver(
            this, toggleReceiver, IntentFilter(ACTION_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        postNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        hide()
        runCatching { unregisterReceiver(toggleReceiver) }
        getSystemService(NotificationManager::class.java).cancel(1)
        instance = null
        super.onDestroy()
    }

    // ---------- notification ----------
    private fun postNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Floating keyboard", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_TOGGLE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Floating keyboard")
            .setContentText("Tap to show / hide")
            .setContentIntent(pi)
            .addAction(0, "Show / Hide keyboard", pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
        nm.notify(1, n)
    }

    // ---------- show / hide ----------
    fun toggle() { if (isShowing) hide() else show() }

    private fun show() {
        val dm = resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.96).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels * 0.02).toInt()
            y = (dm.heightPixels * 0.45).toInt()
        }
        params = lp

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
            background = rounded(Color.parseColor("#F2202124"), 14)
            elevation = dp(8).toFloat()
        }
        container.addView(buildHeader(lp))
        keysBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(keysBox)

        root = container
        wm.addView(container, lp)
        renderKeys()
    }

    private fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        keysBox = null
    }

    // ---------- UI ----------
    private fun buildHeader(lp: WindowManager.LayoutParams): LinearLayout {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val handle = TextView(this).apply {
            text = "⠿  drag to move"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bar.addView(handle, LinearLayout.LayoutParams(0, -2, 1f))
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener { hide() }
        }
        bar.addView(close)

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    root?.let { wm.updateViewLayout(it, lp) }
                }
            }
            true
        }
        return bar
    }

    private fun renderKeys() {
        val box = keysBox ?: return
        box.removeAllViews()
        val rows = if (symbols)
            listOf("1234567890", "@#\$%&-+()/", "*\"':;!?,.")
        else
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

        rows.forEachIndexed { idx, r ->
            val row = row()
            if (idx == 1 && !symbols) row.setPadding(dp(14), 0, dp(14), 0)
            if (idx == 2 && !symbols) {
                row.addView(key(if (shift) "⇧" else "⇪", 1.5f, accent = shift) {
                    shift = !shift; renderKeys()
                })
            }
            r.forEach { ch ->
                val label = if (shift && !symbols) ch.uppercaseChar().toString() else ch.toString()
                row.addView(key(label) {
                    typeText(label)
                    if (shift) { shift = false; renderKeys() }
                })
            }
            if (idx == 2 && !symbols) row.addView(key("⌫", 1.5f) { backspace() })
            box.addView(row)
        }

        val bottom = row()
        bottom.addView(key(if (symbols) "ABC" else "?123", 1.5f) { symbols = !symbols; renderKeys() })
        bottom.addView(key(",", 1f) { typeText(",") })
        bottom.addView(key("space", 5f) { typeText(" ") })
        bottom.addView(key(".", 1f) { typeText(".") })
        if (symbols) bottom.addView(key("⌫", 1.5f) { backspace() })
        bottom.addView(key("⏎", 1.5f, accent = true) { enter() })
        box.addView(bottom)
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun key(label: String, weight: Float = 1f, accent: Boolean = false, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 17f
            setTextColor(Color.WHITE)
            background = rounded(Color.parseColor(if (accent) "#1A73E8" else "#3C4043"), 8)
            layoutParams = LinearLayout.LayoutParams(0, dp(42), weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- text injection ----------
    private fun focusedField(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: windows.firstNotNullOfOrNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }

    private fun currentText(n: AccessibilityNodeInfo): String =
        if (n.isShowingHintText) "" else n.text?.toString() ?: ""

    private fun setText(n: AccessibilityNodeInfo, text: String, cursor: Int) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    private fun typeText(s: String) {
        val n = focusedField() ?: return
        val cur = currentText(n)
        var a = n.textSelectionStart; var b = n.textSelectionEnd
        if (a < 0 || b < 0) { a = cur.length; b = cur.length }
        val start = minOf(a, b).coerceIn(0, cur.length)
        val end = maxOf(a, b).coerceIn(0, cur.length)
        setText(n, cur.replaceRange(start, end, s), start + s.length)
    }

    private fun backspace() {
        val n = focusedField() ?: return
        val cur = currentText(n)
        var a = n.textSelectionStart; var b = n.textSelectionEnd
        if (a < 0 || b < 0) { a = cur.length; b = cur.length }
        var start = minOf(a, b).coerceIn(0, cur.length)
        val end = maxOf(a, b).coerceIn(0, cur.length)
        if (start == end) { if (start == 0) return; start -= 1 }
        setText(n, cur.removeRange(start, end), start)
    }

    private fun enter() {
        val n = focusedField() ?: return
        if (Build.VERSION.SDK_INT >= 30 &&
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return
        if (n.isMultiLine) typeText("\n")
    }
}
