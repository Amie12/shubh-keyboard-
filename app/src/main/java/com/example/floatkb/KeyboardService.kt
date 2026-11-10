package com.example.floatkb

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private var padBar: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    val isShowing get() = root != null

    private var shift = false
    private var symbols = false

    // typing pad
    private var padText = ""
    private var padMode = false
    private var padView: TextView? = null
    private var padToggle: TextView? = null

    // PC key-press mode (needs Shizuku)
    private var pcMode = false
    private var pcToggle: TextView? = null
    private val armed = linkedSetOf<Int>()
    private val modViews = mutableMapOf<Int, TextView>()
    private val heldWith = mutableMapOf<Int, List<Int>>()
    private var lastHint = 0L

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
            y = (dm.heightPixels * 0.40).toInt()
        }
        params = lp

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
            background = rounded(Color.parseColor("#F2202124"), 14)
            elevation = dp(8).toFloat()
        }
        container.addView(buildHeader(lp))
        val pb = buildPad()
        padBar = pb
        container.addView(pb)
        keysBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(keysBox)

        root = container
        wm.addView(container, lp)
        applyMode()
        updatePad()
    }

    private fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        keysBox = null
        padBar = null
        padView = null
        padToggle = null
        pcToggle = null
        modViews.clear()
        armed.clear()
        heldWith.clear()
    }

    // ---------- header ----------
    private fun buildHeader(lp: WindowManager.LayoutParams): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val handle = TextView(this).apply {
            text = "⠿  drag to move"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bar.addView(handle, LinearLayout.LayoutParams(0, -2, 1f))

        val padBtn = TextView(this).apply {
            text = "PAD"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                padMode = !padMode
                if (padMode) pcMode = false
                applyMode()
            }
        }
        padToggle = padBtn
        bar.addView(padBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) })

        val pcBtn = TextView(this).apply {
            text = "PC"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                pcMode = !pcMode
                if (pcMode) {
                    padMode = false
                    ShizukuKeys.connect(this@KeyboardService)
                    hint()
                }
                applyMode()
            }
        }
        pcToggle = pcBtn
        bar.addView(pcBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) })

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

    private fun applyMode() {
        padBar?.visibility = if (pcMode) View.GONE else View.VISIBLE
        padToggle?.background = rounded(Color.parseColor(if (padMode) "#1A73E8" else "#3C4043"), 8)
        pcToggle?.background = rounded(Color.parseColor(if (pcMode) "#1A73E8" else "#3C4043"), 8)
        renderKeys()
    }

    private fun hint() {
        if (ShizukuKeys.isConnected()) return
        val now = System.currentTimeMillis()
        if (now - lastHint < 3000) return
        lastHint = now
        Toast.makeText(this, ShizukuKeys.describe(), Toast.LENGTH_LONG).show()
    }

    // ---------- typing pad ----------
    private fun buildPad(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }
        val view = TextView(this).apply {
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.START
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(Color.parseColor("#2B2D30"), 8)
        }
        padView = view
        bar.addView(view, LinearLayout.LayoutParams(0, -2, 1f))

        bar.addView(smallButton("Copy") { copyPad() })
        bar.addView(smallButton("Clear") { padText = ""; updatePad() })
        return bar
    }

    private fun smallButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = rounded(Color.parseColor("#3C4043"), 8)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, 0, 0) }
        setOnClickListener { onClick() }
    }

    private fun updatePad() {
        val v = padView ?: return
        if (padText.isEmpty()) {
            v.text = "Typing pad (used when no text field is active)"
            v.setTextColor(Color.GRAY)
        } else {
            v.text = padText
            v.setTextColor(Color.WHITE)
        }
    }

    private fun copyPad() {
        if (padText.isEmpty()) return
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("typing pad", padText))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    // ---------- keys ----------
    private fun renderKeys() {
        if (pcMode) renderPcKeys() else renderPhoneKeys()
    }

    private fun renderPhoneKeys() {
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

    private fun renderPcKeys() {
        val box = keysBox ?: return
        box.removeAllViews()
        modViews.clear()

        val r0 = row()
        r0.addView(pcKey("Esc", KeyEvent.KEYCODE_ESCAPE, 1.4f))
        for (i in 1..12) r0.addView(pcKey("F$i", KeyEvent.KEYCODE_F1 + i - 1))
        box.addView(r0)

        val r1 = row()
        r1.addView(pcKey("`", KeyEvent.KEYCODE_GRAVE))
        "1234567890".forEach { r1.addView(pcKey(it.toString(), KeyEvent.KEYCODE_0 + (it - '0'))) }
        r1.addView(pcKey("-", KeyEvent.KEYCODE_MINUS))
        r1.addView(pcKey("=", KeyEvent.KEYCODE_EQUALS))
        r1.addView(pcKey("⌫", KeyEvent.KEYCODE_DEL, 2f))
        box.addView(r1)

        val r2 = row()
        r2.addView(pcKey("Tab", KeyEvent.KEYCODE_TAB, 1.5f))
        "qwertyuiop".forEach { r2.addView(pcKey(it.toString(), KeyEvent.KEYCODE_A + (it - 'a'))) }
        r2.addView(pcKey("[", KeyEvent.KEYCODE_LEFT_BRACKET))
        r2.addView(pcKey("]", KeyEvent.KEYCODE_RIGHT_BRACKET))
        r2.addView(pcKey("\\", KeyEvent.KEYCODE_BACKSLASH))
        box.addView(r2)

        val r3 = row()
        r3.addView(pcKey("Caps", KeyEvent.KEYCODE_CAPS_LOCK, 1.8f))
        "asdfghjkl".forEach { r3.addView(pcKey(it.toString(), KeyEvent.KEYCODE_A + (it - 'a'))) }
        r3.addView(pcKey(";", KeyEvent.KEYCODE_SEMICOLON))
        r3.addView(pcKey("'", KeyEvent.KEYCODE_APOSTROPHE))
        r3.addView(pcKey("Enter", KeyEvent.KEYCODE_ENTER, 2.2f))
        box.addView(r3)

        val r4 = row()
        r4.addView(pcKey("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, 2.3f))
        "zxcvbnm".forEach { r4.addView(pcKey(it.toString(), KeyEvent.KEYCODE_A + (it - 'a'))) }
        r4.addView(pcKey(",", KeyEvent.KEYCODE_COMMA))
        r4.addView(pcKey(".", KeyEvent.KEYCODE_PERIOD))
        r4.addView(pcKey("/", KeyEvent.KEYCODE_SLASH))
        r4.addView(pcKey("Shift", KeyEvent.KEYCODE_SHIFT_RIGHT, 2.2f))
        box.addView(r4)

        val r5 = row()
        r5.addView(pcKey("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, 1.6f))
        r5.addView(pcKey("Alt", KeyEvent.KEYCODE_ALT_LEFT, 1.6f))
        r5.addView(pcKey("Space", KeyEvent.KEYCODE_SPACE, 6f))
        r5.addView(pcKey("←", KeyEvent.KEYCODE_DPAD_LEFT, 1.2f))
        r5.addView(pcKey("↑", KeyEvent.KEYCODE_DPAD_UP, 1.2f))
        r5.addView(pcKey("↓", KeyEvent.KEYCODE_DPAD_DOWN, 1.2f))
        r5.addView(pcKey("→", KeyEvent.KEYCODE_DPAD_RIGHT, 1.2f))
        box.addView(r5)
    }

    private fun paintKey(tv: TextView, on: Boolean) {
        tv.background = rounded(Color.parseColor(if (on) "#1A73E8" else "#3C4043"), 6)
    }

    private fun pcKey(label: String, code: Int, weight: Float = 1f): TextView {
        val isMod = code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT ||
            code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_ALT_LEFT
        val tv = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 12f
            maxLines = 1
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), weight).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
        }
        paintKey(tv, armed.contains(code))
        if (isMod) {
            modViews[code] = tv
            tv.setOnClickListener {
                if (!armed.add(code)) armed.remove(code)
                paintKey(tv, armed.contains(code))
            }
        } else {
            tv.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.6f
                        pcDown(code)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1f
                        pcUp(code)
                    }
                }
                true
            }
        }
        return tv
    }

    private fun metaFor(mods: Collection<Int>): Int {
        var m = 0
        for (c in mods) {
            m = m or when (c) {
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT ->
                    KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                KeyEvent.KEYCODE_CTRL_LEFT ->
                    KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                KeyEvent.KEYCODE_ALT_LEFT ->
                    KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
                else -> 0
            }
        }
        return m
    }

    private fun pcDown(code: Int) {
        if (!ShizukuKeys.isConnected()) {
            ShizukuKeys.connect(this)
            hint()
            return
        }
        val mods = armed.toList()
        heldWith[code] = mods
        for (m in mods) ShizukuKeys.key(m, true, metaFor(listOf(m)))
        ShizukuKeys.key(code, true, metaFor(mods))
    }

    private fun pcUp(code: Int) {
        val mods = heldWith.remove(code) ?: return
        ShizukuKeys.key(code, false, metaFor(mods))
        for (m in mods.reversed()) ShizukuKeys.key(m, false, 0)
        if (mods.isNotEmpty()) {
            armed.removeAll(mods.toSet())
            for (m in mods) modViews[m]?.let { paintKey(it, false) }
        }
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

    // ---------- text injection (normal mode) ----------
    private fun focusedField(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: windows.firstNotNullOfOrNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }

    private fun targetField(): AccessibilityNodeInfo? = if (padMode) null else focusedField()

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
        val n = targetField()
        if (n == null) {
            padText += s
            updatePad()
            return
        }
        val cur = currentText(n)
        var a = n.textSelectionStart; var b = n.textSelectionEnd
        if (a < 0 || b < 0) { a = cur.length; b = cur.length }
        val start = minOf(a, b).coerceIn(0, cur.length)
        val end = maxOf
