package net.rathour.handywatch

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * HandyWatch's look, after HandyDash's grey pages: a near-black background, grey rounded cards,
 * light text, and the status-dial colours. Sizes are drawn for a 320-wide screen and scaled to
 * the real one (the watch's 320 × 480 screen is 1:1).
 */
object Ui {
    const val BG = 0xFF1D1D1B.toInt()
    const val CARD = 0xFF2B2B29.toInt()
    const val CARD2 = 0xFF363634.toInt()
    const val TRACK = 0xFF3D3D3A.toInt()
    const val LINE = 0xFF464643.toInt()
    const val TEXT = 0xFFEDEDEA.toInt()
    const val TEXT2 = 0xFFA8A8A3.toInt()
    const val GREY = 0xFF8A8A86.toInt()
    const val BLUE = 0xFF1E88E5.toInt()
    const val GREEN = 0xFF43A047.toInt()
    const val RED = 0xFFE53935.toInt()
    const val AMBER = 0xFFF9A825.toInt()
    private val AVATAR = intArrayOf(0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFE53935.toInt(), 0xFF8E24AA.toInt(),
        0xFFF4511E.toInt(), 0xFF00897B.toInt(), 0xFF6D4C41.toInt(), 0xFF3949AB.toInt())

    var u = 1f; private set

    fun init(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        u = minOf(minOf(dm.widthPixels, dm.heightPixels) / 320f, dm.density * 1.3f).coerceAtLeast(0.75f)
    }

    fun px(v: Number) = (v.toFloat() * u + 0.5f).toInt()
    fun avatarColour(s: String) = AVATAR[(s.hashCode() and 0x7FFFFFFF) % AVATAR.size]

    fun round(colour: Int, radius: Float, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(colour); cornerRadius = radius * u
        if (stroke != 0) setStroke(px(1), stroke)
    }

    fun ripple(colour: Int, radius: Float) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(colour, radius), round(0xFFFFFFFF.toInt(), radius))

    fun text(ctx: Context, s: CharSequence = "", size: Float = 14f, colour: Int = TEXT, bold: Boolean = false, lines: Int = 0): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u)
            setTextColor(colour)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
            includeFontPadding = false
            setLineSpacing(0f, 1.12f)
        }

    /** A rounded button with words. */
    fun button(ctx: Context, s: String, colour: Int = CARD2, ink: Int = TEXT, size: Float = 14f, onClick: () -> Unit): TextView =
        text(ctx, s, size, ink, true).apply {
            gravity = Gravity.CENTER
            background = ripple(colour, 999f)
            setPadding(px(14), px(9), px(14), px(9))
            minHeight = px(38)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** A round icon button. */
    fun iconButton(ctx: Context, icon: String, size: Int = 44, colour: Int = CARD2, ink: Int = TEXT, desc: String = icon, onClick: () -> Unit): IconView =
        IconView(ctx, icon, ink).apply {
            background = ripple(colour, size / 2f)
            contentDescription = desc
            pad = 0.26f
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(px(size), px(size))
        }

    fun card(ctx: Context, colour: Int = CARD): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = round(colour, 16f)
        setPadding(px(12), px(10), px(12), px(12))
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
           l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(px(l), px(t), px(r), px(b)) }

    fun row(ctx: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
    }

    /** A row of choices, one selected. */
    fun segmented(ctx: Context, labels: List<String>, selected: Int, size: Float = 13f, onPick: (Int) -> Unit): LinearLayout {
        val r = row(ctx)
        r.background = round(CARD2, 999f)
        r.setPadding(px(3), px(3), px(3), px(3))
        val views = ArrayList<TextView>()
        fun paint(sel: Int) { views.forEachIndexed { i, v -> v.background = if (i == sel) round(0xFF5A5A56.toInt(), 999f) else null; v.setTextColor(if (i == sel) TEXT else TEXT2) } }
        labels.forEachIndexed { i, s ->
            val v = text(ctx, s, size, TEXT2, true).apply {
                gravity = Gravity.CENTER; setPadding(px(6), px(7), px(6), px(7)); isClickable = true
                setOnClickListener { paint(i); onPick(i) }
            }
            views += v
            r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint(selected)
        return r
    }

    fun ago(ms: Long): String {
        val s = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60} min"
            s < 86400 -> "${s / 3600} h"
            else -> "${s / 86400} d"
        }
    }

    fun duration(secs: Long): String = when {
        secs < 60 -> "${secs} s"
        secs < 3600 -> "${secs / 60} min"
        else -> "${secs / 3600} h ${secs % 3600 / 60} min"
    }
}

/** Stroke icons on a 24-unit grid, drawn in code (no image files). */
class IconView(ctx: Context, var icon: String, var ink: Int = Ui.TEXT) : View(ctx) {
    var pad = 0.18f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val r = RectF()

    fun set(name: String, colour: Int = ink) { icon = name; ink = colour; invalidate() }

    override fun onDraw(c: Canvas) {
        val size = minOf(width, height).toFloat()
        val inner = size * (1 - 2 * pad)
        c.save()
        c.translate((width - inner) / 2f, (height - inner) / 2f)
        c.scale(inner / 24f, inner / 24f)
        p.color = ink; p.strokeWidth = 2f; p.style = Paint.Style.STROKE
        drawIcon(c)
        c.restore()
    }

    private fun fill() { p.style = Paint.Style.FILL }
    private fun stroke() { p.style = Paint.Style.STROKE }
    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, p)
    private fun poly(c: Canvas, close: Boolean, vararg xy: Float) {
        path.reset(); path.moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) { path.lineTo(xy[i], xy[i + 1]); i += 2 }
        if (close) path.close()
        c.drawPath(path, p)
    }
    private fun rrect(c: Canvas, l: Float, t: Float, rr: Float, b: Float, rad: Float) { r.set(l, t, rr, b); c.drawRoundRect(r, rad, rad, p) }

    private fun drawIcon(c: Canvas) {
        when (icon) {
            "bell" -> { path.reset(); path.moveTo(6f, 17f); path.lineTo(6f, 11f); path.cubicTo(6f, 7f, 8.5f, 4.5f, 12f, 4.5f); path.cubicTo(15.5f, 4.5f, 18f, 7f, 18f, 11f); path.lineTo(18f, 17f); c.drawPath(path, p)
                line(c, 4f, 17f, 20f, 17f); r.set(10f, 18.5f, 14f, 21.5f); c.drawArc(r, 0f, 180f, false, p) }
            "music" -> { line(c, 9f, 18f, 9f, 5f); line(c, 9f, 5f, 19f, 3f); line(c, 19f, 3f, 19f, 16f); fill(); c.drawCircle(6.5f, 18f, 2.6f, p); c.drawCircle(16.5f, 16f, 2.6f, p) }
            "pad" -> { rrect(c, 3f, 7f, 21f, 18f, 5f); line(c, 8f, 10.5f, 8f, 14.5f); line(c, 6f, 12.5f, 10f, 12.5f); fill(); c.drawCircle(15.5f, 11.5f, 1.2f, p); c.drawCircle(17.5f, 14f, 1.2f, p) }
            "dial" -> { p.color = 0xFF5A5A56.toInt(); c.drawCircle(12f, 12f, 8f, p); p.color = ink; r.set(4f, 4f, 20f, 20f); c.drawArc(r, -90f, 240f, false, p) }
            "cog" -> { c.drawCircle(12f, 12f, 3f, p); for (i in 0 until 8) { c.save(); c.rotate(i * 45f, 12f, 12f); line(c, 12f, 3f, 12f, 5.5f); c.restore() }; c.drawCircle(12f, 12f, 6.5f, p) }
            "play" -> { fill(); poly(c, true, 8f, 5f, 19f, 12f, 8f, 19f) }
            "pause" -> { fill(); rrect(c, 6.5f, 5f, 10f, 19f, 1f); rrect(c, 14f, 5f, 17.5f, 19f, 1f) }
            "playpause" -> { fill(); poly(c, true, 4f, 6f, 12f, 12f, 4f, 18f); rrect(c, 14f, 6f, 16.5f, 18f, 0.8f); rrect(c, 18.5f, 6f, 21f, 18f, 0.8f) }
            "next" -> { fill(); poly(c, true, 6f, 6f, 15f, 12f, 6f, 18f); rrect(c, 16f, 6f, 18.5f, 18f, 0.8f) }
            "prev" -> { fill(); poly(c, true, 18f, 6f, 9f, 12f, 18f, 18f); rrect(c, 5.5f, 6f, 8f, 18f, 0.8f) }
            "plus" -> { line(c, 12f, 5f, 12f, 19f); line(c, 5f, 12f, 19f, 12f) }
            "minus" -> line(c, 5f, 12f, 19f, 12f)
            "volup" -> { speaker(c); r.set(11f, 7f, 19f, 17f); c.drawArc(r, -60f, 120f, false, p); r.set(9f, 3.5f, 23f, 20.5f); c.drawArc(r, -55f, 110f, false, p) }
            "voldown" -> { speaker(c); r.set(11f, 7f, 19f, 17f); c.drawArc(r, -60f, 120f, false, p) }
            "camera" -> { rrect(c, 3f, 7f, 21f, 19f, 3f); poly(c, false, 8f, 7f, 9.5f, 4.5f, 14.5f, 4.5f, 16f, 7f); c.drawCircle(12f, 13f, 3.5f, p) }
            "flip" -> { r.set(5f, 5f, 19f, 19f); c.drawArc(r, 200f, 150f, false, p); c.drawArc(r, 20f, 150f, false, p); poly(c, false, 16.5f, 4f, 18.5f, 7.5f, 15f, 8.5f); poly(c, false, 7.5f, 20f, 5.5f, 16.5f, 9f, 15.5f) }
            "back" -> { poly(c, false, 10f, 6f, 4f, 12f, 10f, 18f); line(c, 4f, 12f, 20f, 12f) }
            "home" -> { poly(c, false, 4f, 11f, 12f, 4f, 20f, 11f); poly(c, false, 6.5f, 9.5f, 6.5f, 20f, 17.5f, 20f, 17.5f, 9.5f) }
            "recents" -> rrect(c, 5f, 5f, 19f, 19f, 2.5f)
            "shade" -> { rrect(c, 4f, 4f, 20f, 20f, 3f); line(c, 4f, 10f, 20f, 10f); poly(c, false, 9f, 14f, 12f, 17f, 15f, 14f) }
            "up" -> poly(c, false, 5f, 15f, 12f, 8f, 19f, 15f)
            "down" -> poly(c, false, 5f, 9f, 12f, 16f, 19f, 9f)
            "left" -> poly(c, false, 15f, 5f, 8f, 12f, 15f, 19f)
            "right" -> poly(c, false, 9f, 5f, 16f, 12f, 9f, 19f)
            "keyboard" -> { rrect(c, 2.5f, 6f, 21.5f, 18f, 2.5f); fill(); for (x in 0..4) { c.drawCircle(6f + x * 3f, 10f, 0.9f, p) }; stroke(); line(c, 8f, 14.5f, 16f, 14.5f) }
            "touch" -> { rrect(c, 4f, 3f, 20f, 21f, 3f); fill(); c.drawCircle(12f, 11f, 2.2f, p); stroke(); line(c, 4f, 16f, 20f, 16f); line(c, 12f, 16f, 12f, 21f) }
            "enter" -> { poly(c, false, 19f, 5f, 19f, 13f, 5f, 13f); poly(c, false, 9f, 9f, 5f, 13f, 9f, 17f) }
            "bksp" -> { poly(c, true, 8f, 5f, 21f, 5f, 21f, 19f, 8f, 19f, 2f, 12f); line(c, 11f, 9f, 17f, 15f); line(c, 17f, 9f, 11f, 15f) }
            "send" -> { poly(c, true, 3f, 4f, 21f, 12f, 3f, 20f, 6f, 12f); line(c, 6f, 12f, 12f, 12f) }
            "x" -> { line(c, 6f, 6f, 18f, 18f); line(c, 18f, 6f, 6f, 18f) }
            "ring" -> { rrect(c, 7f, 2.5f, 17f, 21.5f, 2.5f); line(c, 10.5f, 18.5f, 13.5f, 18.5f); r.set(1f, 7f, 7f, 15f); c.drawArc(r, 120f, 120f, false, p); r.set(17f, 7f, 23f, 15f); c.drawArc(r, -60f, 120f, false, p) }
            "lock" -> { rrect(c, 5f, 10.5f, 19f, 20.5f, 2.5f); r.set(8f, 3.5f, 16f, 13f); c.drawArc(r, 180f, 180f, false, p); line(c, 8f, 8.3f, 8f, 10.5f); line(c, 16f, 8.3f, 16f, 10.5f) }
            "refresh" -> { r.set(5f, 5f, 19f, 19f); c.drawArc(r, -60f, 300f, false, p); poly(c, false, 15f, 3.5f, 18.5f, 6f, 15.5f, 9f) }
            "phone" -> { rrect(c, 7f, 2.5f, 17f, 21.5f, 2.5f); line(c, 10.5f, 18.5f, 13.5f, 18.5f) }
            "watch" -> { rrect(c, 6f, 6f, 18f, 18f, 3f); line(c, 9f, 6f, 9.5f, 2.5f); line(c, 15f, 6f, 14.5f, 2.5f); line(c, 9f, 18f, 9.5f, 21.5f); line(c, 15f, 18f, 14.5f, 21.5f) }
            "trash" -> { line(c, 4f, 7f, 20f, 7f); poly(c, false, 6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f); poly(c, false, 9f, 7f, 9.5f, 4f, 14.5f, 4f, 15f, 7f) }
            "tap" -> { c.drawCircle(12f, 12f, 3f, p); c.drawCircle(12f, 12f, 7.5f, p) }
            "hold" -> { fill(); c.drawCircle(12f, 12f, 4f, p); stroke(); c.drawCircle(12f, 12f, 8f, p) }
            "data" -> { line(c, 8f, 20f, 8f, 5f); poly(c, false, 4f, 9f, 8f, 5f, 12f, 9f); line(c, 16f, 4f, 16f, 19f); poly(c, false, 12f, 15f, 16f, 19f, 20f, 15f) }
        }
        stroke()
    }

    private fun speaker(c: Canvas) { poly(c, true, 3f, 9.5f, 7f, 9.5f, 11f, 6f, 11f, 18f, 7f, 14.5f, 3f, 14.5f) }
}

/** One status dial: a grey track, a coloured arc with round ends, and the percentage in the middle. */
class DialView(ctx: Context) : View(ctx) {
    var progress = 0
    var colour = Ui.BLUE
    var label = ""
    var sub = ""
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.TRACK }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT2; textAlign = Paint.Align.CENTER }
    private val r = RectF()

    fun set(p: Int, c: Int, l: String, s: String = "") { progress = p.coerceIn(0, 100); colour = c; label = l; sub = s; invalidate() }

    override fun onDraw(c: Canvas) {
        val size = minOf(width, height).toFloat()
        val sw = size * 0.11f
        track.strokeWidth = sw; arc.strokeWidth = sw; arc.color = colour
        val cx = width / 2f; val cy = height / 2f; val rad = size / 2f - sw / 2f - 1f
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        c.drawCircle(cx, cy, rad, track)
        if (progress > 0) c.drawArc(r, -90f, 360f * progress / 100f, false, arc)
        txt.textSize = size * (if (sub.isEmpty()) 0.26f else 0.22f)
        val ty = if (sub.isEmpty()) cy - (txt.descent() + txt.ascent()) / 2 else cy - size * 0.02f
        c.drawText(label, cx, ty, txt)
        if (sub.isNotEmpty()) { small.textSize = size * 0.085f; c.drawText(sub, cx, cy + size * 0.15f, small) }
    }
}

/** The watch's touchpad: one finger moves the phone's pointer, a quick tap clicks, a still hold is a long press, two fingers scroll. */
class TouchpadView(ctx: Context, private val send: (String, Float, Float) -> Unit) : View(ctx) {
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var moved = 0f
    private var twoFinger = false
    private var held = false
    private var accX = 0f
    private var accY = 0f
    private var lastSend = 0L
    private val holdCheck = Runnable { if (!twoFinger && moved < Ui.px(8)) { held = true; send("hold", 0f, 0f); performHapticFeedback(0) } }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x331E88E5 }
    private var touching = false
    private var tx = 0f
    private var ty = 0f

    init {
        background = Ui.round(Ui.CARD, 16f, Ui.LINE)
        isClickable = true
    }

    override fun onDraw(c: Canvas) { if (touching) c.drawCircle(tx, ty, Ui.px(26).toFloat(), dot) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; downAt = e.eventTime; moved = 0f; twoFinger = false; held = false; accX = 0f; accY = 0f
                touching = true; tx = e.x; ty = e.y; invalidate()
                send("pad", 1f, 0f)
                postDelayed(holdCheck, 550)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { twoFinger = true; removeCallbacks(holdCheck); lastX = avgX(e); lastY = avgY(e) }
            MotionEvent.ACTION_MOVE -> {
                val x = if (twoFinger) avgX(e) else e.x
                val y = if (twoFinger) avgY(e) else e.y
                val dx = x - lastX; val dy = y - lastY
                lastX = x; lastY = y
                moved += Math.abs(dx) + Math.abs(dy)
                accX += dx / Ui.u; accY += dy / Ui.u
                tx = e.x; ty = e.y; invalidate()
                val now = e.eventTime
                if (now - lastSend >= (if (twoFinger) 120 else 33) && (Math.abs(accX) + Math.abs(accY) >= 0.5f)) {
                    send(if (twoFinger) "scroll" else "move", accX, accY)
                    accX = 0f; accY = 0f; lastSend = now
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdCheck)
                touching = false; invalidate()
                if (!twoFinger && !held && moved < Ui.px(10) && e.eventTime - downAt < 300) send("tap", 0f, 0f)
                else if (accX != 0f || accY != 0f) send(if (twoFinger) "scroll" else "move", accX, accY)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(holdCheck); touching = false; invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    private fun avgX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun avgY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }
}
