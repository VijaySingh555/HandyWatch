package net.rathour.handywatch

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * The watch's screen: a thin status line (time, link, phone battery), five pages
 * (Notifications, Music, Control, Dials, Settings) and a tab bar along the bottom.
 */
class WatchScreen(private val a: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private val svc get() = WatchService.instance
    lateinit var root: LinearLayout
    private lateinit var clock: TextView
    private lateinit var linkDot: View
    private lateinit var linkText: TextView
    private lateinit var batText: TextView
    private lateinit var pages: FrameLayout
    private val pageViews = ArrayList<View>()
    private val tabs = ArrayList<IconView>()
    private var page = 0
    private var resumed = false

    fun build(): View {
        Ui.init(a)
        root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        // status line
        val top = Ui.row(a).apply { setPadding(Ui.px(12), Ui.px(5), Ui.px(12), Ui.px(3)) }
        clock = Ui.text(a, "", 15f, Ui.TEXT, true)
        top.addView(clock)
        top.addView(View(a), Ui.lp(0, 1, 1f))
        linkDot = View(a).apply { background = Ui.round(Ui.GREY, 999f) }
        top.addView(linkDot, Ui.lp(Ui.px(8), Ui.px(8), r = 5))
        linkText = Ui.text(a, "", 12f, Ui.TEXT2, lines = 1)
        top.addView(linkText, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 10))
        batText = Ui.text(a, "", 12f, Ui.TEXT2, true)
        top.addView(batText)
        root.addView(top, Ui.lp())
        pages = FrameLayout(a)
        root.addView(pages, Ui.lp(h = 0, weight = 1f))
        listOf(notificationsPage(), mediaPage(), controlPage(), dialsPage(), settingsPage()).forEach { pageViews += it; pages.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        // tab bar
        val bar = Ui.row(a, Gravity.CENTER).apply { setPadding(Ui.px(6), Ui.px(4), Ui.px(6), Ui.px(6)); setBackgroundColor(Ui.BG) }
        listOf("bell" to "Notifications", "music" to "Music", "pad" to "Control", "dial" to "Status dials", "cog" to "Settings").forEachIndexed { i, (ic, d) ->
            val t = IconView(a, ic, Ui.TEXT2).apply {
                pad = 0.27f; contentDescription = d; isClickable = true
                setOnClickListener { show(i) }
            }
            tabs += t
            bar.addView(t, Ui.lp(0, Ui.px(44), 1f, 2, 0, 2, 0))
        }
        root.addView(bar, Ui.lp())
        show(Prefs.page(a).coerceIn(0, 4))
        return root
    }

    fun show(i: Int) {
        page = i
        Prefs.set(a, "page", i)
        pageViews.forEachIndexed { j, v -> v.visibility = if (j == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { j, t -> t.background = if (j == i) Ui.round(Ui.CARD2, 999f) else null; t.set(t.icon, if (j == i) Ui.TEXT else Ui.TEXT2) }
        if (i == 3) startDials() else stopDials()
        if (i == 4) paintSettings()
        if (::camView.isInitialized) updateLive()
        if (i != 2 || controlTab != 2) svc?.let { if (it.connected && padShown) { it.cmd("pad", "on" to false); padShown = false } }
        hideKeyboard()
    }

    fun onResume() {
        resumed = true
        hook()
        tickClock()
        if (page == 3) startDials()
    }

    fun onPause() {
        resumed = false
        if (liveOn) { liveOn = false; svc?.live(false) }
        svc?.onChange = null
        stopDials()
        main.removeCallbacksAndMessages(null)
    }

    private fun hook() {
        val s = svc
        if (s == null) { main.postDelayed({ if (resumed) hook() }, 200); return }
        s.onChange = { what -> main.post { onChange(what) } }
        paintAll()
    }

    private fun onChange(what: String) {
        when {
            what.startsWith("toast:") -> Toast.makeText(a, what.substring(6), Toast.LENGTH_SHORT).show()
            what == "notifs" -> paintNotifications()
            what == "media" || what == "ring" -> paintMedia()
            what == "cam" || what == "frame" -> paintCamera()
            what.startsWith("snap:") -> snapped(what.substring(5))
            what == "hid" -> { paintKeysLabel(); if (page == 4) paintSettings() }
            what == "shot" -> { shotUntil = System.currentTimeMillis() + 1500; paintCamera(); main.postDelayed({ paintCamera() }, 1600) }
            what == "exit" -> a.finishAffinity()
            else -> { paintAll(); updateLive() }
        }
    }

    private fun paintAll() { paintStatus(); paintNotifications(); paintMedia(); paintCamera(); paintKeysLabel(); if (page == 4) paintSettings() }

    private fun tickClock() {
        clock.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        paintStatus()
        main.postDelayed({ if (resumed) tickClock() }, 15000)
    }

    private fun paintStatus() {
        val s = svc
        val up = s?.connected == true
        linkDot.background = Ui.round(if (up) Ui.GREEN else Ui.GREY, 999f)
        linkText.text = if (up) s!!.phone.ifBlank { "Phone" } else "Not connected"
        batText.text = if (up && s!!.battery >= 0) "${s.battery}%" + (if (s.charging) "+" else "") else ""
    }

    private fun hideKeyboard() {
        val im = a.getSystemService(Activity.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        im.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun scroller(content: View): ScrollView = ScrollView(a).apply {
        isFillViewport = true; isVerticalScrollBarEnabled = false
        addView(content, FrameLayout.LayoutParams(-1, -2))
    }

    private fun column(): LinearLayout = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.px(8), Ui.px(4), Ui.px(8), Ui.px(8)) }

    private fun header(title: String): LinearLayout {
        val r = Ui.row(a).apply { setPadding(Ui.px(4), Ui.px(4), Ui.px(2), Ui.px(6)) }
        r.addView(Ui.text(a, title, 18f, Ui.TEXT, true), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    private fun banner(col: LinearLayout): TextView {
        val b = Ui.text(a, "", 13f, Ui.TEXT).apply {
            background = Ui.round(Ui.WARN, 12f); setPadding(Ui.px(10), Ui.px(8), Ui.px(10), Ui.px(8)); visibility = View.GONE
            setOnClickListener { svc?.reconnect(); Toast.makeText(a, "Connecting…", Toast.LENGTH_SHORT).show() }
        }
        col.addView(b, Ui.lp(b = 8))
        return b
    }

    // ================= Notifications
    private lateinit var nList: LinearLayout
    private lateinit var nBanner: TextView
    private lateinit var nClear: TextView
    private lateinit var nDetail: FrameLayout
    private var detailKey: String? = null

    private fun notificationsPage(): View {
        val frame = FrameLayout(a)
        val col = column()
        val h = header("Notifications")
        nClear = Ui.button(a, "Clear all", size = 12f) { confirmClear() }.apply { setPadding(Ui.px(10), Ui.px(5), Ui.px(10), Ui.px(5)); minHeight = Ui.px(30) }
        h.addView(nClear)
        col.addView(h)
        nBanner = banner(col)
        nList = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(nList)
        frame.addView(scroller(col), FrameLayout.LayoutParams(-1, -1))
        nDetail = FrameLayout(a).apply { setBackgroundColor(Ui.BG); visibility = View.GONE; isClickable = true }
        frame.addView(nDetail, FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    private fun confirmClear() {
        val both = Prefs.dismissBoth(a)
        Ui.dialog(a).setTitle("Clear all?").setMessage(if (both) "They're dismissed on the phone too." else "They stay on the phone.")
            .setPositiveButton("Clear") { _, _ -> svc?.dismissAll() }.setNegativeButton("Cancel", null).show()
    }

    private fun paintNotifications() {
        val s = svc
        nList.removeAllViews()
        nBanner.visibility = View.GONE
        if (s != null && !s.connected) { nBanner.text = "Phone not connected (${s.status}). Tap to try now."; nBanner.visibility = View.VISIBLE }
        else if (s != null && !s.notifyAccess) { nBanner.text = "On the phone, turn on HandyWatch's notification access."; nBanner.visibility = View.VISIBLE }
        val list = s?.notifs?.values?.toList() ?: emptyList()
        nClear.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        if (list.isEmpty()) nList.addView(Ui.text(a, "No notifications from your phone", 14f, Ui.TEXT2).apply { gravity = Gravity.CENTER; setPadding(0, Ui.px(60), 0, 0) }, Ui.lp())
        for (n in list) nList.addView(notificationCard(n), Ui.lp(b = 6))
        val k = detailKey
        if (k != null && s?.notifs?.containsKey(k) != true) closeDetail() // dismissed elsewhere
    }

    private fun notificationCard(n: JSONObject): View {
        val key = n.optString("key")
        val app = n.optString("app")
        val card = Ui.card(a).apply { setPadding(Ui.px(10), Ui.px(8), Ui.px(10), Ui.px(10)) }
        val top = Ui.row(a)
        top.addView(avatar(app, 22), Ui.lp(Ui.px(22), Ui.px(22), r = 7))
        top.addView(Ui.text(a, app + " · " + Ui.ago(n.optLong("when")), 12f, Ui.TEXT2, lines = 1), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top)
        val title = n.optString("title")
        if (title.isNotBlank()) card.addView(Ui.text(a, title, 15f, Ui.TEXT, true, 1), Ui.lp(t = 4))
        val body = n.optString("body")
        if (body.isNotBlank()) card.addView(Ui.text(a, body, 14f, Ui.TEXT2, lines = 2), Ui.lp(t = 3))
        swipeable(card, { svc?.dismissedHere(key) }) { openDetail(n) }
        return card
    }

    private fun avatar(app: String, size: Int) = Ui.text(a, app.take(1).uppercase(), size * 0.55f, 0xFFFFFFFF.toInt(), true).apply {
        gravity = Gravity.CENTER; background = Ui.round(Ui.avatarColour(app), 999f)
    }

    /** Swipe sideways to dismiss; tap to open. Vertical drags still scroll the list. */
    @SuppressLint("ClickableViewAccessibility")
    private fun swipeable(v: View, onDismiss: () -> Unit, onTap: () -> Unit) {
        val slop = android.view.ViewConfiguration.get(a).scaledTouchSlop
        var x0 = 0f; var y0 = 0f; var dragging = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; dragging = false; view.isPressed = true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) { dragging = true; view.parent.requestDisallowInterceptTouchEvent(true); view.isPressed = false }
                    if (dragging) { view.translationX = dx; view.alpha = (1f - Math.abs(dx) / view.width).coerceIn(0.2f, 1f) }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    val dx = e.rawX - x0
                    if (dragging) {
                        if (Math.abs(dx) > view.width * 0.33f) view.animate().translationX(Math.signum(dx) * view.width).alpha(0f).setDuration(140).withEndAction { onDismiss() }.start()
                        else view.animate().translationX(0f).alpha(1f).setDuration(140).start()
                    } else if (Math.abs(dx) < slop && Math.abs(e.rawY - y0) < slop) onTap()
                }
                android.view.MotionEvent.ACTION_CANCEL -> { view.isPressed = false; view.animate().translationX(0f).alpha(1f).setDuration(140).start() }
            }
            true
        }
    }

    private fun openDetail(n: JSONObject) {
        val key = n.optString("key")
        detailKey = key
        nDetail.removeAllViews()
        val col = column()
        val bar = Ui.row(a).apply { setPadding(0, Ui.px(2), 0, Ui.px(8)) }
        bar.addView(Ui.iconButton(a, "back", 40, desc = "Back") { closeDetail() })
        bar.addView(View(a), Ui.lp(0, 1, 1f))
        bar.addView(Ui.button(a, "Dismiss", size = 13f) { closeDetail(); svc?.dismissedHere(key) })
        col.addView(bar)
        val app = n.optString("app")
        val who = Ui.row(a)
        who.addView(avatar(app, 26), Ui.lp(Ui.px(26), Ui.px(26), r = 8))
        who.addView(Ui.text(a, app, 14f, Ui.TEXT, true, 1), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(who, Ui.lp(l = 2))
        val whenMs = n.optLong("when")
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(whenMs)) + " (" + Ui.ago(whenMs) + ")"
        col.addView(Ui.text(a, time, 12.5f, Ui.TEXT2), Ui.lp(t = 6, l = 2))
        val title = n.optString("title")
        if (title.isNotBlank()) col.addView(Ui.text(a, title, 17f, Ui.TEXT, true), Ui.lp(t = 10, l = 2, r = 2))
        val body = n.optString("body")
        if (body.isNotBlank()) col.addView(Ui.text(a, body, 15f, Ui.TEXT).apply { setTextIsSelectable(false) }, Ui.lp(t = 8, l = 2, r = 2, b = 12))
        nDetail.addView(scroller(col), FrameLayout.LayoutParams(-1, -1))
        nDetail.visibility = View.VISIBLE
    }

    private fun closeDetail() { detailKey = null; nDetail.visibility = View.GONE; nDetail.removeAllViews() }

    /** The watch's back button: close the open notification first. */
    fun back(): Boolean { if (page == 0 && detailKey != null) { closeDetail(); return true }; return false }

    // ================= Music
    private lateinit var mApp: TextView
    private lateinit var mTitle: TextView
    private lateinit var mArtist: TextView
    private lateinit var mPlay: IconView
    private lateinit var mVolBar: View
    private lateinit var mVolRest: View
    private lateinit var mVolTrack: LinearLayout
    private lateinit var mVolText: TextView
    private lateinit var mRing: TextView

    /** One line each, scrolling sideways when too long, so the buttons below never move. */
    private fun marquee(size: Float, colour: Int, bold: Boolean) = Ui.text(a, "", size, colour, bold).apply {
        gravity = Gravity.CENTER; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1; isSelected = true; setHorizontallyScrolling(true)
    }

    private fun setIfChanged(t: TextView, s: String) { if (t.text.toString() != s) t.text = s }

    private fun mediaPage(): View {
        val col = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(header("Music"))
        mApp = marquee(12f, Ui.TEXT2, false)
        col.addView(mApp, Ui.lp(h = Ui.px(16), t = 2))
        mTitle = marquee(20f, Ui.TEXT, true)
        col.addView(mTitle, Ui.lp(h = Ui.px(28), t = 6, l = 4, r = 4))
        mArtist = marquee(14f, Ui.TEXT2, false)
        col.addView(mArtist, Ui.lp(h = Ui.px(20), t = 2, l = 4, r = 4))
        val btns = Ui.row(a, Gravity.CENTER)
        btns.addView(Ui.iconButton(a, "prev", 58, desc = "Previous") { svc?.cmd("media", "a" to "prev") })
        mPlay = Ui.iconButton(a, "play", 78, Ui.PRIMARY, Ui.ON_PRIMARY, "Play or pause") { svc?.cmd("media", "a" to "playpause") }
        btns.addView(mPlay, Ui.lp(Ui.px(78), Ui.px(78), l = 16, r = 16))
        btns.addView(Ui.iconButton(a, "next", 58, desc = "Next") { svc?.cmd("media", "a" to "next") })
        col.addView(btns, Ui.lp(t = 14))
        val vol = Ui.row(a)
        vol.addView(Ui.iconButton(a, "voldown", 46, desc = "Volume down") { svc?.mediaKey("voldown") })
        mVolTrack = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; background = Ui.round(Ui.TRACK, 999f) }
        mVolBar = View(a).apply { background = Ui.round(Ui.TEXT2, 999f) }
        mVolRest = View(a)
        mVolTrack.addView(mVolBar, LinearLayout.LayoutParams(0, -1, 0f))
        mVolTrack.addView(mVolRest, LinearLayout.LayoutParams(0, -1, 1f))
        vol.addView(mVolTrack, Ui.lp(0, Ui.px(8), 1f, 10, 0, 10, 0))
        vol.addView(Ui.iconButton(a, "volup", 46, desc = "Volume up") { svc?.mediaKey("volup") })
        col.addView(vol, Ui.lp(t = 16, l = 4, r = 4))
        mVolText = Ui.text(a, "", 12f, Ui.TEXT2).apply { gravity = Gravity.CENTER }
        col.addView(mVolText, Ui.lp(h = Ui.px(16), t = 2))
        val extra = Ui.row(a, Gravity.CENTER)
        mRing = Ui.button(a, "Find phone", size = 13f) { svc?.let { it.cmd("ring", "on" to !it.ringing) } }
        extra.addView(mRing, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, r = 4))
        extra.addView(Ui.button(a, "Lock phone", size = 13f) { svc?.cmd("lock") }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = 4))
        col.addView(extra, Ui.lp(t = 12))
        return scroller(col)
    }

    private fun paintMedia() {
        val s = svc
        val m = s?.media
        val has = m?.optBoolean("has") == true
        setIfChanged(mApp, if (s?.connected != true) "Phone not connected" else if (has) m!!.optString("app") else "Nothing playing on the phone")
        setIfChanged(mTitle, if (has) m!!.optString("title").ifBlank { "Unknown track" } else "")
        setIfChanged(mArtist, if (has) m!!.optString("artist") else "")
        mPlay.set(if (has && m!!.optBoolean("playing")) "pause" else "play", Ui.ON_PRIMARY)
        val max = m?.optInt("max", 0) ?: 0
        val vol = m?.optInt("vol", 0) ?: 0
        val part = if (max > 0) vol.toFloat() / max else 0f
        mVolBar.layoutParams = (mVolBar.layoutParams as LinearLayout.LayoutParams).apply { weight = part }
        mVolRest.layoutParams = (mVolRest.layoutParams as LinearLayout.LayoutParams).apply { weight = 1f - part }
        mVolTrack.requestLayout()
        mVolText.text = if (max > 0) "Volume $vol of $max" else ""
        val ringing = s?.ringing == true
        mRing.text = if (ringing) "Stop ringing" else "Find phone"
        mRing.background = Ui.ripple(if (ringing) Ui.RED else Ui.CARD2, 999f)
        mRing.setTextColor(if (ringing) 0xFFFFFFFF.toInt() else Ui.TEXT)
    }

    // ================= Control
    private var controlTab = 0
    private var padShown = false
    private var liveOn = false
    private val controlViews = ArrayList<View>()
    private lateinit var camView: ImageView
    private lateinit var camNote: TextView
    private lateinit var camBadge: TextView
    private lateinit var camShutter: IconView
    private lateinit var camBlankBtn: IconView
    private lateinit var camCloseBtn: IconView
    private var shotUntil = 0L
    private lateinit var camFlash: View

    private fun controlPage(): View {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.px(8), Ui.px(4), Ui.px(8), Ui.px(6)) }
        controlTab = Prefs.p(a).getInt("control_tab", 0)
        val tabsRow = Ui.segmented(a, listOf("Camera", "Keys", "Pad", "Type"), controlTab, 12.5f) { i -> controlShow(i) }
        col.addView(tabsRow, Ui.lp(b = 6))
        val holder = FrameLayout(a)
        col.addView(holder, Ui.lp(h = 0, weight = 1f))
        listOf(cameraTab(), keysTab(), padTab(), typeTab()).forEach { controlViews += it; holder.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        controlShow(controlTab)
        return col
    }

    private fun controlShow(i: Int) {
        controlTab = i
        Prefs.set(a, "control_tab", i)
        controlViews.forEachIndexed { j, v -> v.visibility = if (j == i) View.VISIBLE else View.GONE }
        if (i != 2 && padShown) { svc?.cmd("pad", "on" to false); padShown = false }
        if (i != 3) hideKeyboard()
        updateLive()
    }

    /** Frames come only while the camera tab is on screen and the phone's camera is open. */
    private fun updateLive() {
        val s = svc
        val want = resumed && page == 2 && controlTab == 0 && s?.connected == true && s.camOpen
        if (want != liveOn) { liveOn = want; s?.live(want) }
    }

    private fun cameraTab(): View {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        // the preview, as big as the page allows
        val box = FrameLayout(a).apply { background = Ui.round(0xFF000000.toInt(), 14f); clipToOutline = true; isClickable = true
            setOnClickListener { svc?.let { if (!it.camOpen) it.cmd("cam", "a" to "open") } } }
        camView = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        box.addView(camView, FrameLayout.LayoutParams(-1, -1))
        camFlash = View(a).apply { setBackgroundColor(0xFFFFFFFF.toInt()); alpha = 0f }
        camNote = Ui.text(a, "", 15f, 0xFFEDEDEA.toInt(), true).apply { gravity = Gravity.CENTER; setPadding(Ui.px(12), 0, Ui.px(12), 0) }
        box.addView(camNote, FrameLayout.LayoutParams(-1, -1))
        camBadge = Ui.text(a, "", 13f, 0xFFFFFFFF.toInt(), true).apply { background = Ui.round(Ui.RED, 999f); setPadding(Ui.px(9), Ui.px(3), Ui.px(9), Ui.px(3)); visibility = View.GONE }
        box.addView(camBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.px(6) })
        box.addView(camFlash, FrameLayout.LayoutParams(-1, -1))
        col.addView(box, Ui.lp(h = 0, weight = 1f))
        // photo or video, and the timer
        val modes = Ui.row(a)
        modes.addView(Ui.segmented(a, listOf("Photo", "Video"), Prefs.p(a).getInt("cam_mode", 0), 12f) { i -> Prefs.set(a, "cam_mode", i); paintCamera() },
            Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, r = 4))
        modes.addView(Ui.segmented(a, listOf("Now", "5 s", "10 s"), Prefs.p(a).getInt("timer", 0), 12f) { i -> Prefs.set(a, "timer", i) },
            Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f, l = 4))
        col.addView(modes, Ui.lp(t = 6))
        // close, switch, shutter, black screen
        val row = Ui.row(a, Gravity.CENTER)
        camCloseBtn = Ui.iconButton(a, "x", 42, desc = "Close the phone's camera") { svc?.cmd("cam", "a" to "close") }
        row.addView(camCloseBtn, Ui.lp(Ui.px(42), Ui.px(42)))
        row.addView(Ui.iconButton(a, "flip", 46, desc = "Switch camera") { svc?.cmd("cam", "a" to "lens") }, Ui.lp(Ui.px(46), Ui.px(46), l = 10))
        camShutter = Ui.iconButton(a, "camera", 66, Ui.PRIMARY, Ui.ON_PRIMARY, "Shutter") {
            val d = when (Prefs.p(a).getInt("timer", 0)) { 1 -> 5; 2 -> 10; else -> 0 }
            val video = Prefs.p(a).getInt("cam_mode", 0) == 1
            svc?.let {
                when {
                    !it.connected -> Toast.makeText(a, "The phone isn't connected", Toast.LENGTH_SHORT).show()
                    !it.camOpen -> Toast.makeText(a, "Tap the preview to start the camera", Toast.LENGTH_SHORT).show()
                    it.camRec -> it.cmd("cam", "a" to "shoot", "video" to true)
                    else -> it.cmd("cam", "a" to "shoot", "delay" to d, "video" to video)
                }
            }
        }
        row.addView(camShutter, Ui.lp(Ui.px(66), Ui.px(66), l = 14, r = 14))
        camBlankBtn = Ui.iconButton(a, "blank", 46, desc = "Black out the phone's screen") { svc?.let { it.cmd("cam", "a" to "blank", "on" to !it.camBlank) } }
        row.addView(camBlankBtn, Ui.lp(Ui.px(46), Ui.px(46), r = 10))
        row.addView(View(a), Ui.lp(Ui.px(42), Ui.px(42)))
        col.addView(row, Ui.lp(t = 6))
        return col
    }

    private fun paintCamera() {
        if (!::camView.isInitialized) return
        val s = svc
        val open = s?.connected == true && s.camOpen
        val video = Prefs.p(a).getInt("cam_mode", 0) == 1
        val showingShot = System.currentTimeMillis() < shotUntil && s?.shot != null
        camView.setImageBitmap(if (showingShot) s!!.shot else if (open) s!!.frame else null)
        camNote.text = when {
            s?.connected != true -> "Phone not connected"
            s.camCount > 0 -> s.camCount.toString()
            !s.camOpen -> "Tap here to start the camera"
            s.frame == null && !showingShot -> "Starting the preview…"
            else -> ""
        }
        camNote.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (if (s?.camCount ?: 0 > 0) 64f else 15f) * Ui.u)
        camBadge.visibility = if (s?.camRec == true || s?.camBlank == true) View.VISIBLE else View.GONE
        camBadge.text = (if (s?.camRec == true) "● %d:%02d".format(s.camSecs / 60, s.camSecs % 60) else "") +
            (if (s?.camRec == true && s.camBlank) "  ·  " else "") + (if (s?.camBlank == true) "Phone screen off" else "")
        camShutter.set(if (s?.camRec == true) "stop" else if (video) "rec" else "camera", Ui.ON_PRIMARY)
        camBlankBtn.set(if (s?.camBlank == true) "unblank" else "blank", Ui.TEXT)
        camCloseBtn.alpha = if (open) 1f else 0.35f
        updateLive()
    }

    private lateinit var keysLabel: TextView

    private fun paintKeysLabel() {
        if (!::keysLabel.isInitialized) return
        val s = svc
        keysLabel.text = when {
            s == null -> ""
            Prefs.keysMode(a) == "keys" && s.hid.connected -> "Real keys (Bluetooth keyboard)"
            else -> "Swipes on the phone · Settings › Keys for real keys"
        }
    }

    /** The phone has just taken the photo (or started or stopped a video): flash and say so. The watch has already buzzed. */
    private fun snapped(kind: String) {
        if (kind == "photo" && ::camFlash.isInitialized) {
            camFlash.animate().cancel(); camFlash.alpha = 0.9f
            camFlash.animate().alpha(0f).setDuration(450).start()
        }
        Toast.makeText(a, when (kind) { "recstart" -> "Recording"; "recstop" -> "Recording stopped"; else -> "Photo taken" }, Toast.LENGTH_SHORT).show()
    }

    private fun keysTab(): View {
        // a 3 × 3 pad of big keys: volume either side of up, Back bottom-left, Home bottom-right
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        keysLabel = Ui.text(a, "", 11.5f, Ui.TEXT2, lines = 1).apply { gravity = Gravity.CENTER }
        grid.addView(keysLabel, Ui.lp(b = 5))
        fun key(ic: String, k: String, desc: String, colour: Int = Ui.CARD2, ink: Int = Ui.TEXT) =
            Ui.iconButton(a, ic, 60, colour, ink, desc, radius = 20f) { svc?.pressKey(k) }.apply { pad = 0.3f }
        val rows = listOf(
            listOf(key("voldown", "voldown", "Volume down"), key("up", "up", "Up"), key("volup", "volup", "Volume up")),
            listOf(key("left", "left", "Left"), key("playpause", "play", "Play or pause", Ui.PRIMARY, Ui.ON_PRIMARY), key("right", "right", "Right")),
            listOf(key("back", "back", "Back"), key("down", "down", "Down"), key("home", "home", "Home")))
        for ((ri, r) in rows.withIndex()) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for ((ci, k) in r.withIndex()) row.addView(k, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(if (ci == 0) 0 else Ui.px(4), 0, if (ci == 2) 0 else Ui.px(4), 0) })
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(0, if (ri == 0) 0 else Ui.px(4), 0, if (ri == 2) 0 else Ui.px(4)) })
        }
        return grid
    }

    private fun padTab(): View {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val pad = TouchpadView(a) { what, dx, dy ->
            val s = svc
            if (s != null && s.connected) when (what) {
                "pad" -> if (!padShown) { s.cmd("pad", "on" to true); padShown = true }
                "move" -> s.cmd("move", "dx" to dx.toDouble(), "dy" to dy.toDouble(), "speed" to Prefs.padSpeed(a))
                "scroll" -> s.cmd("scroll", "dx" to dx.toDouble(), "dy" to dy.toDouble())
                "tap" -> s.cmd("tap")
                "hold" -> s.cmd("hold")
            }
        }
        col.addView(pad, Ui.lp(h = 0, weight = 1f))
        col.addView(Ui.text(a, "Drag to move · tap to click · hold for a long press · two fingers to scroll", 11.5f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, Ui.lp(t = 5))
        val row = Ui.row(a, Gravity.CENTER)
        row.addView(Ui.iconButton(a, "back", 44, desc = "Back") { svc?.pressKey("back") }, Ui.lp(Ui.px(44), Ui.px(44), r = 6))
        row.addView(Ui.iconButton(a, "home", 44, desc = "Home") { svc?.pressKey("home") }, Ui.lp(Ui.px(44), Ui.px(44), r = 6))
        row.addView(Ui.button(a, "Tap", size = 13f) { svc?.cmd("tap") }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, r = 4))
        row.addView(Ui.button(a, "Hold", size = 13f) { svc?.cmd("hold") }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = 4))
        col.addView(row, Ui.lp(t = 6))
        return col
    }

    private fun typeTab(): View {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(a, "Tap a text box on the phone, then type here.", 12.5f, Ui.TEXT2), Ui.lp(b = 6, l = 2))
        val box = EditText(a).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16f * Ui.u); setTextColor(Ui.TEXT); setHintTextColor(Ui.GREY)
            hint = "Type here"; background = Ui.round(Ui.CARD, 14f, Ui.LINE); setPadding(Ui.px(12), Ui.px(10), Ui.px(12), Ui.px(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false); minLines = 2; maxLines = 4; gravity = Gravity.TOP or Gravity.START
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        fun sendText() {
            val t = box.text.toString()
            if (t.isEmpty()) return
            if (svc?.type(t) == true) { box.setText(""); Toast.makeText(a, "Sent to the phone", Toast.LENGTH_SHORT).show() }
        }
        box.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendText(); true } else false }
        col.addView(box, Ui.lp())
        val row = Ui.row(a, Gravity.CENTER)
        row.addView(Ui.button(a, "Send", Ui.BLUE, 0xFFFFFFFF.toInt(), 14f) { sendText() }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, r = 6))
        row.addView(Ui.iconButton(a, "bksp", 46, desc = "Delete the last letter") { svc?.backspace() }, Ui.lp(Ui.px(46), Ui.px(46), r = 6))
        row.addView(Ui.iconButton(a, "enter", 46, desc = "Enter") { svc?.enter() }, Ui.lp(Ui.px(46), Ui.px(46)))
        col.addView(row, Ui.lp(t = 8))
        return scroller(col)
    }

    // ================= Status dials
    private lateinit var dList: LinearLayout
    private lateinit var dStatus: TextView
    private var dialsOn = false
    private var lastStates = HashMap<String, String>()
    private var dialsBusy = false

    private fun dialsPage(): View {
        val col = column()
        val h = header("Status dials")
        h.addView(Ui.iconButton(a, "refresh", 34, Ui.CARD2, Ui.TEXT, "Refresh now") { fetchDials() })
        col.addView(h)
        dStatus = Ui.text(a, "", 12f, Ui.TEXT2)
        col.addView(dStatus, Ui.lp(b = 6, l = 4))
        dList = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(dList)
        return scroller(col)
    }

    private val dialTick = object : Runnable {
        override fun run() {
            if (!dialsOn) return
            fetchDials()
            main.postDelayed(this, Prefs.refreshSecs(a) * 1000L)
        }
    }

    private fun startDials() { if (!resumed || dialsOn) return; dialsOn = true; main.removeCallbacks(dialTick); main.post(dialTick) }
    private fun stopDials() { dialsOn = false; main.removeCallbacks(dialTick) }

    private fun fetchDials() {
        val s = svc ?: return
        if (dialsBusy) return
        if (Prefs.readKey(a).isBlank()) { dStatus.text = "Add your read key in Settings › Status dials"; return }
        dialsBusy = true
        if (dList.childCount == 0) dStatus.text = "Loading…"
        s.fetchDials { code, body ->
            dialsBusy = false
            paintDials(code, body)
        }
    }

    private fun paintDials(code: Int, body: String) {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val now = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        if (code == 403 || o?.optString("error") == "bad key") { dStatus.text = "The bridge refused the read key"; return }
        if (code == 0 || o == null || !o.optBoolean("ok")) {
            dStatus.text = if (code == 0) "Couldn't reach the bridge (" + (if (svc?.connected == true && Prefs.viaPhone(a)) "through the phone" else svc?.internet() ?: "") + ")" else "The bridge answered $code"
            return
        }
        val arr = o.optJSONArray("tasks")
        val server = o.optLong("time", System.currentTimeMillis() / 1000)
        dStatus.text = "Updated $now" + (if (o.optBoolean("demo")) " · demo tasks (nothing reported yet)" else "")
        val tasks = ArrayList<JSONObject>()
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { tasks += it }
        // alerts when a task changes to done or error
        val states = HashMap<String, String>()
        for (t in tasks) {
            val id = t.optString("app") + "|" + t.optString("task")
            val st = t.optString("state")
            states[id] = st
            val before = lastStates[id]
            if (before != null && before != st && (st == "done" || st == "error")) {
                val title = t.optString("app") + " · " + t.optString("task")
                val text = if (st == "done") "Finished" else "Failed: " + t.optString("msg")
                Toast.makeText(a, "$title: $text", Toast.LENGTH_LONG).show()
                if (Prefs.dialAlerts(a)) svc?.dialAlert(title, text, id.hashCode())
            }
        }
        lastStates = states
        // running first, then by app
        val order = mapOf("running" to 0, "paused" to 1, "stalled" to 2, "error" to 3, "done" to 4)
        tasks.sortWith(compareBy({ order[it.optString("state")] ?: 5 }, { it.optString("app").lowercase() }))
        dList.removeAllViews()
        if (tasks.isEmpty()) { dList.addView(Ui.text(a, "No tasks", 14f, Ui.TEXT2).apply { gravity = Gravity.CENTER; setPadding(0, Ui.px(50), 0, 0) }, Ui.lp()); return }
        val apps = tasks.map { it.optString("app") }.distinct()
        for (app in apps) {
            dList.addView(Ui.text(a, app, 12.5f, Ui.TEXT2, true), Ui.lp(t = 6, b = 4, l = 4))
            for (t in tasks.filter { it.optString("app") == app }) dList.addView(dialRow(t, server), Ui.lp(b = 6))
        }
    }

    private fun stateColour(st: String) = when (st) { "done" -> Ui.GREEN; "error" -> Ui.RED; "running" -> Ui.BLUE; else -> Ui.GREY }
    private fun stateWord(st: String) = when (st) { "done" -> "Done"; "error" -> "Failed"; "running" -> "Running"; "paused" -> "Paused"; "stalled" -> "Stalled"; else -> st }

    private fun dialRow(t: JSONObject, server: Long): View {
        val st = t.optString("state")
        val p = t.optInt("progress")
        val card = Ui.card(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.px(8), Ui.px(8), Ui.px(10), Ui.px(8)) }
        val d = DialView(a).apply { set(p, stateColour(st), "$p%") }
        card.addView(d, Ui.lp(Ui.px(54), Ui.px(54), r = 10))
        val txt = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(Ui.text(a, t.optString("task"), 14.5f, Ui.TEXT, true, 1))
        val eta = if (t.isNull("eta")) -1L else t.optLong("eta", -1)
        val age = (server - t.optLong("updated", server)).coerceAtLeast(0)
        val line2 = stateWord(st) + (if (eta > 0 && st == "running") " · " + Ui.duration(eta) + " left" else "") + " · " + (if (age < 60) "now" else Ui.duration(age) + " ago")
        txt.addView(Ui.text(a, line2, 12f, stateColour(st).let { if (st == "running") Ui.TEXT2 else it }, lines = 1), Ui.lp(t = 2))
        val msg = t.optString("msg")
        if (msg.isNotBlank()) txt.addView(Ui.text(a, msg, 12f, Ui.TEXT2, lines = 2), Ui.lp(t = 2))
        card.addView(txt, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.setOnClickListener { dialDetail(t, line2) }
        return card
    }

    private fun dialDetail(t: JSONObject, line2: String) {
        val st = t.optString("state")
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.px(14), Ui.px(14), Ui.px(14), Ui.px(8)); setBackgroundColor(Ui.CARD) }
        val d = DialView(a).apply { set(t.optInt("progress"), stateColour(st), "${t.optInt("progress")}%", stateWord(st)) }
        col.addView(d, Ui.lp(Ui.px(180), Ui.px(180)))
        col.addView(Ui.text(a, t.optString("task"), 17f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }, Ui.lp(t = 10))
        col.addView(Ui.text(a, t.optString("app"), 13f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, Ui.lp(t = 2))
        col.addView(Ui.text(a, line2, 13f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, Ui.lp(t = 6))
        if (t.optString("msg").isNotBlank()) col.addView(Ui.text(a, t.optString("msg"), 14f, Ui.TEXT).apply { gravity = Gravity.CENTER }, Ui.lp(t = 6))
        Ui.dialog(a).setView(col).setPositiveButton("Close", null).show()
    }

    // ================= Settings
    private lateinit var sCol: LinearLayout

    private fun settingsPage(): View {
        sCol = column()
        return scroller(sCol)
    }

    private fun section(title: String, help: String = ""): LinearLayout {
        val c = Ui.card(a)
        c.addView(Ui.text(a, title, 15f, Ui.TEXT, true), Ui.lp(b = 4))
        if (help.isNotEmpty()) c.addView(Ui.text(a, help, 12f, Ui.TEXT2), Ui.lp(b = 8))
        sCol.addView(c, Ui.lp(b = 8))
        return c
    }

    private fun switchRow(parent: LinearLayout, label: String, key: String, def: Boolean) {
        val r = Ui.row(a)
        r.addView(Ui.text(a, label, 14f), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(a).apply { isChecked = Prefs.p(a).getBoolean(key, def); setOnCheckedChangeListener { _, on -> Prefs.set(a, key, on) } }
        r.addView(sw)
        parent.addView(r, Ui.lp(t = 2, b = 2))
    }

    private fun valueRow(parent: LinearLayout, label: String, value: String, onClick: () -> Unit) {
        val r = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = Ui.ripple(Ui.CARD2, 12f); setPadding(Ui.px(10), Ui.px(7), Ui.px(10), Ui.px(7)); isClickable = true; setOnClickListener { onClick() } }
        r.addView(Ui.text(a, label, 12f, Ui.TEXT2))
        r.addView(Ui.text(a, value, 14f, Ui.TEXT, lines = 2), Ui.lp(t = 2))
        parent.addView(r, Ui.lp(t = 3, b = 3))
    }

    fun paintSettings() {
        if (!::sCol.isInitialized) return
        sCol.removeAllViews()
        val s = svc
        sCol.addView(header("Settings"))
        // look
        val lk = section("Look")
        lk.addView(Ui.segmented(a, listOf("OLED black", "Grey"), if (Ui.grey) 1 else 0, 12.5f) { i ->
            Prefs.set(a, "theme", if (i == 1) "grey" else "oled"); main.post { (a as MainActivity).rebuild() }
        }, Ui.lp())
        // phone
        val ph = section("Phone", "HandyWatch must be running on the phone too, set as the phone.")
        valueRow(ph, "Connect to", Prefs.phoneName(a).ifBlank { "Choose your phone" }) { choosePhone() }
        ph.addView(Ui.text(a, if (s?.connected == true) "Connected to ${s.phone}" else (s?.status ?: "Not running"), 13f, if (s?.connected == true) Ui.GREEN else Ui.TEXT2), Ui.lp(t = 4))
        val pr = Ui.row(a)
        pr.addView(Ui.button(a, "Reconnect", size = 13f) { svc?.reconnect(); Toast.makeText(a, "Connecting…", Toast.LENGTH_SHORT).show() }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, r = 4))
        pr.addView(Ui.button(a, "Pair", size = 13f) { open(Settings.ACTION_BLUETOOTH_SETTINGS) }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = 4))
        ph.addView(pr, Ui.lp(t = 8))
        // keys
        val ks = section("Keys", "Real keys: the watch acts as a Bluetooth keyboard for the phone (arrows, volume, play/pause, Back, Home and typing). Swipes: through HandyWatch control on the phone.")
        ks.addView(Ui.segmented(a, listOf("Real keys", "Swipes"), if (Prefs.keysMode(a) == "keys") 0 else 1, 12.5f) { i ->
            svc?.setKeysMode(if (i == 0) "keys" else "swipes") ?: Prefs.set(a, "keys_mode", if (i == 0) "keys" else "swipes")
        }, Ui.lp(b = 6))
        val st = svc?.keysStatus() ?: ""
        ks.addView(Ui.text(a, st, 13f, if (svc?.hid?.connected == true) Ui.GREEN else Ui.TEXT2), Ui.lp(b = 6))
        if (Prefs.keysMode(a) == "keys" && svc?.hid?.connected != true && svc?.hid?.state != "unavailable")
            ks.addView(Ui.button(a, "Connect the keyboard", size = 13f) { svc?.hid?.enable(true); svc?.hid?.connect() }, Ui.lp())
        // mobile data
        val md = section("Mobile data", "On the phone: turn on Bluetooth tethering. Here: Bluetooth › the phone's cog › Internet access.")
        md.addView(Ui.text(a, s?.internet() ?: "", 13f, if (s?.internet()?.contains("phone") == true) Ui.GREEN else Ui.TEXT), Ui.lp(b = 8))
        md.addView(Ui.button(a, "Open Bluetooth settings", size = 13f) { open(Settings.ACTION_BLUETOOTH_SETTINGS) }, Ui.lp())
        // notifications
        val nt = section("Notifications")
        switchRow(nt, "Buzz on the watch", "buzz", true)
        switchRow(nt, "Dismissing here dismisses on the phone", "dismiss_both", true)
        // dials
        val dl = section("Status dials", "From your rathour.net bridge. The key stays on this watch.")
        valueRow(dl, "Bridge address", Prefs.bridge(a)) { edit("Bridge address", Prefs.bridge(a), false) { v -> Prefs.set(a, "bridge", v.ifBlank { Prefs.DEFAULT_BRIDGE }) } }
        valueRow(dl, "Read key", if (Prefs.readKey(a).isBlank()) "Not set" else "••••••••") { edit("Read key", Prefs.readKey(a), true) { v -> Prefs.set(a, "read_key", v) } }
        dl.addView(Ui.text(a, "Refresh every", 12f, Ui.TEXT2), Ui.lp(t = 6, b = 4))
        val opts = listOf(5, 10, 30, 60)
        dl.addView(Ui.segmented(a, opts.map { "$it s" }, opts.indexOf(Prefs.refreshSecs(a)).coerceAtLeast(1), 12.5f) { i -> Prefs.set(a, "refresh", opts[i]) }, Ui.lp(b = 6))
        switchRow(dl, "Alert when a task finishes or fails", "dial_alerts", true)
        switchRow(dl, "Fetch through the phone", "via_phone", true)
        // touchpad
        val tp = section("Touchpad speed")
        tp.addView(Ui.segmented(a, listOf("Slow", "Normal", "Fast"), Prefs.padSpeed(a), 12.5f) { i -> Prefs.set(a, "pad_speed", i) }, Ui.lp())
        // this device
        val dv = section("This watch")
        dv.addView(Ui.button(a, "Battery: let HandyWatch run", size = 13f) { appDetails() }, Ui.lp(b = 6))
        dv.addView(Ui.button(a, "Use this device as the phone", size = 13f) { switchRole() }, Ui.lp(b = 6))
        dv.addView(Ui.button(a, "Exit HandyWatch", Ui.CARD2, Ui.RED, 13f) { svc?.exitAll() ?: a.finishAffinity() }, Ui.lp())
        sCol.addView(Ui.text(a, "HandyWatch v${Prefs.VERSION} (${Prefs.BUILD_DATE})", 11.5f, Ui.GREY).apply { gravity = Gravity.CENTER }, Ui.lp(t = 4, b = 8))
    }

    @SuppressLint("MissingPermission")
    private fun choosePhone() {
        val ad = BluetoothAdapter.getDefaultAdapter()
        if (ad == null || !ad.isEnabled) { Toast.makeText(a, "Switch Bluetooth on first", Toast.LENGTH_SHORT).show(); return }
        val devs = runCatching { ad.bondedDevices.toList() }.getOrDefault(emptyList()).sortedBy { (it.name ?: it.address).lowercase() }
        if (devs.isEmpty()) {
            Ui.dialog(a).setTitle("No paired phones").setMessage("Pair the phone in Bluetooth settings first.")
                .setPositiveButton("Open Bluetooth") { _, _ -> open(Settings.ACTION_BLUETOOTH_SETTINGS) }.setNegativeButton("Cancel", null).show()
            return
        }
        Ui.dialog(a).setTitle("Your phone").setItems(devs.map { it.name ?: it.address }.toTypedArray()) { _, i ->
            Prefs.setPhone(a, devs[i].address, devs[i].name ?: devs[i].address)
            svc?.reconnect()
            paintSettings()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun edit(title: String, value: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(a).apply {
            setText(value); setSingleLine()
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = FrameLayout(a).apply { setPadding(Ui.px(16), Ui.px(4), Ui.px(16), 0); addView(input) }
        Ui.dialog(a).setTitle(title).setView(box)
            .setPositiveButton("Save") { _, _ -> save(input.text.toString().trim()); paintSettings(); lastStates.clear(); if (page == 3) fetchDials() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun open(action: String) { try { a.startActivity(Intent(action)) } catch (_: Exception) { Toast.makeText(a, "Couldn't open that setting", Toast.LENGTH_SHORT).show() } }

    private fun appDetails() {
        try { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName))) } catch (_: Exception) { }
        Toast.makeText(a, "Allow it to run in the background (no battery limits), and Autostart if offered", Toast.LENGTH_LONG).show()
    }

    private fun switchRole() {
        Ui.dialog(a).setTitle("Use as the phone?").setMessage("This device will share its notifications and music instead of showing the phone's.")
            .setPositiveButton("Switch") { _, _ -> (a as MainActivity).setRole(Prefs.ROLE_PHONE) }.setNegativeButton("Cancel", null).show()
    }
}
