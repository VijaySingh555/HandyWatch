package net.rathour.handywatch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The phone: HandyWatch control, an accessibility service that carries out the watch's Control
 * page: arrow keys (as swipes), Back, Home, Recents and the notification shade, a pointer moved
 * from the watch's touchpad (tap, hold, scroll), and typing into the box that has focus.
 * Android needs the user to switch it on in Settings > Accessibility.
 */
class ControlAccess : AccessibilityService() {
    companion object {
        @Volatile var instance: ControlAccess? = null
        fun on() = instance != null
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var pointer: PointerView? = null
    private var px = 0f
    private var py = 0f
    private var w = 1080
    private var h = 2000

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        measure()
        px = w / 2f; py = h / 2f
        PhoneService.instance?.let { it.send("ctl", "on" to true) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        hidePointer()
        if (instance === this) instance = null
        PhoneService.instance?.send("ctl", "on" to false)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun measure() {
        val dm = DisplayMetrics()
        wm?.defaultDisplay?.getRealMetrics(dm)
        if (dm.widthPixels > 0) { w = dm.widthPixels; h = dm.heightPixels }
    }

    // ---- keys
    fun key(k: String) {
        measure()
        val cx = w / 2f
        val cy = h / 2f
        val dx = w * 0.30f
        val dy = h * 0.25f
        when (k) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifs" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            // arrows move the content as a key would: right shows the next thing (finger right to left)
            "right" -> swipe(cx + dx, cy, cx - dx, cy, 220)
            "left" -> swipe(cx - dx, cy, cx + dx, cy, 220)
            "down" -> swipe(cx, cy + dy, cx, cy - dy, 260)
            "up" -> swipe(cx, cy - dy, cx, cy + dy, 260)
        }
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        if (Build.VERSION.SDK_INT < 24) { PhoneService.instance?.toWatch("Gestures need Android 7 or later on the phone"); return }
        val p = Path().apply { moveTo(x1.coerceIn(1f, w - 1f), y1.coerceIn(1f, h - 1f)); lineTo(x2.coerceIn(1f, w - 1f), y2.coerceIn(1f, h - 1f)) }
        runCatching { dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null) }
    }

    // ---- the pointer (touchpad)
    private val hideLater = Runnable { hidePointer() }

    fun showPointer(on: Boolean) {
        if (!on) { hidePointer(); return }
        main.removeCallbacks(hideLater)
        main.postDelayed(hideLater, 15000)
        if (pointer != null || Build.VERSION.SDK_INT < 22) return
        measure()
        val v = PointerView(this)
        val size = (resources.displayMetrics.density * 28).toInt()
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (px - size / 2).toInt(); lp.y = (py - size / 2).toInt()
        try { wm?.addView(v, lp); pointer = v } catch (_: Exception) { }
    }

    private fun hidePointer() {
        main.removeCallbacks(hideLater)
        pointer?.let { runCatching { wm?.removeView(it) } }
        pointer = null
    }

    fun move(dx: Float, dy: Float, speed: Int) {
        showPointer(true)
        val f = (w / 320f) * when (speed) { 0 -> 0.7f; 2 -> 2.0f; else -> 1.2f }
        px = (px + dx * f).coerceIn(0f, w - 1f)
        py = (py + dy * f).coerceIn(0f, h - 1f)
        val v = pointer ?: return
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.x = (px - lp.width / 2).toInt(); lp.y = (py - lp.height / 2).toInt()
        runCatching { wm?.updateViewLayout(v, lp) }
    }

    fun tap(long: Boolean) {
        if (Build.VERSION.SDK_INT < 24) return
        showPointer(true)
        pointer?.flash()
        val p = Path().apply { moveTo(px, py) }
        runCatching { dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, if (long) 650 else 40)).build(), null, null) }
    }

    /** Two-finger drag on the watch: a swipe from the pointer, the same way round as on a phone. */
    fun scroll(dx: Float, dy: Float) {
        showPointer(true)
        val f = w / 320f * 1.5f
        swipe(px, py, px + dx * f, py + dy * f, 280)
    }

    // ---- typing into the focused box
    private fun focused(): AccessibilityNodeInfo? {
        val n = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (n == null || !n.isEditable) { PhoneService.instance?.toWatch("Tap a text box on the phone first"); return null }
        return n
    }

    private fun currentText(n: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) return ""
        return n.text?.toString() ?: ""
    }

    private fun setText(n: AccessibilityNodeInfo, t: String) {
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, t.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, t.length)
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    fun type(text: String) { val n = focused() ?: return; setText(n, currentText(n) + text) }
    fun backspace() { val n = focused() ?: return; val t = currentText(n); if (t.isNotEmpty()) setText(n, t.dropLast(1)) }

    fun enter() {
        val n = focused() ?: return
        if (Build.VERSION.SDK_INT >= 30) n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        else setText(n, currentText(n) + "\n")
    }
}

/** The pointer: a white ring with a dark edge, visible on any background. */
class PointerView(ctx: Context) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var flashOn = false
    fun flash() { flashOn = true; invalidate(); postDelayed({ flashOn = false; invalidate() }, 180) }
    override fun onDraw(c: Canvas) {
        val r = width / 2f
        fill.color = if (flashOn) 0xCC1E88E5.toInt() else 0x551E88E5
        c.drawCircle(r, r, r * 0.8f, fill)
        ring.strokeWidth = r * 0.22f; ring.color = 0xFF000000.toInt(); c.drawCircle(r, r, r * 0.78f, ring)
        ring.strokeWidth = r * 0.12f; ring.color = 0xFFFFFFFF.toInt(); c.drawCircle(r, r, r * 0.78f, ring)
    }
}
