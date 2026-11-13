package net.rathour.handywatch

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * One app for both devices: the first time, it asks whether this is the phone or the watch.
 * The phone gets a set-up screen (what's allowed, and shortcuts to switch things on); the
 * watch gets the five pages in WatchScreen.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var watch: WatchScreen? = null
    private var phoneCol: LinearLayout? = null
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        build()
        intent?.getIntExtra("page", -1)?.let { if (it >= 0) watch?.show(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val p = intent.getIntExtra("page", -1)
        if (p >= 0) watch?.show(p)
    }

    private fun build() {
        watch = null; phoneCol = null
        when (Prefs.role(this)) {
            Prefs.ROLE_WATCH -> { val w = WatchScreen(this); watch = w; setContentView(w.build()) }
            Prefs.ROLE_PHONE -> setContentView(phoneScreen())
            else -> setContentView(rolePicker())
        }
        if (Prefs.role(this).isNotEmpty()) { askPermissions(); Sys.startService(this) }
    }

    fun setRole(r: String) {
        watch?.onPause()
        Sys.stopServices(this)
        Prefs.setRole(this, r)
        Prefs.set(this, "exited", false)
        build()
        if (resumed) { watch?.onResume(); paintPhone() }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (Prefs.role(this).isNotEmpty() && Prefs.p(this).getBoolean("exited", false)) { Prefs.set(this, "exited", false); Sys.startService(this) }
        watch?.onResume()
        hookPhone()
    }

    override fun onPause() {
        resumed = false
        watch?.onPause()
        PhoneService.instance?.onUi = null
        main.removeCallbacksAndMessages(null)
        super.onPause()
    }

    // ---- permissions (Bluetooth on Android 12+, notifications on 13+, the camera on the phone)
    private fun granted(p: String) = Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        val p = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        if (Prefs.role(this) == Prefs.ROLE_PHONE) {
            p += Manifest.permission.CAMERA
            if (Build.VERSION.SDK_INT <= 28) p += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val missing = p.filter { !granted(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Sys.stopServices(this)
        Sys.startService(this) // the link starts again with the new permissions
        paintPhone()
    }

    // ---- first run: which device is this?
    private fun rolePicker(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Ui.BG); setPadding(Ui.px(16), Ui.px(16), Ui.px(16), Ui.px(16)) }
        col.addView(IconView(this, "watch", Ui.TEXT), Ui.lp(Ui.px(56), Ui.px(56)))
        col.addView(Ui.text(this, "HandyWatch", 22f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }, Ui.lp(t = 8))
        col.addView(Ui.text(this, "Install it on both devices. Which one is this?", 14f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, Ui.lp(t = 6, b = 18))
        col.addView(Ui.button(this, "This is my phone", Ui.CARD2, Ui.TEXT, 16f) { setRole(Prefs.ROLE_PHONE) }, Ui.lp(b = 10))
        col.addView(Ui.button(this, "This is my watch", Ui.BLUE, 0xFFFFFFFF.toInt(), 16f) { setRole(Prefs.ROLE_WATCH) }, Ui.lp())
        col.addView(Ui.text(this, "The phone shares its notifications, music and mobile data; the watch shows and controls them.", 12.5f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, Ui.lp(t = 18))
        return col
    }

    // ---- the phone's screen
    private fun phoneScreen(): View {
        val sc = ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.px(10), Ui.px(14), Ui.px(10), Ui.px(14)) }
        sc.addView(col, FrameLayout.LayoutParams(-1, -2))
        phoneCol = col
        paintPhone()
        return sc
    }

    private fun hookPhone() {
        if (Prefs.role(this) != Prefs.ROLE_PHONE) return
        val s = PhoneService.instance
        if (s == null) { main.postDelayed({ if (resumed) hookPhone() }, 200); return }
        s.onUi = { runOnUiThread { paintPhone() } }
        paintPhone()
    }

    private fun notificationsAllowed() = (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)
    private fun batteryFree() = Build.VERSION.SDK_INT < 23 || (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun paintPhone() {
        val col = phoneCol ?: return
        col.removeAllViews()
        val s = PhoneService.instance
        val up = s?.link?.connected == true
        val head = Ui.card(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.px(12), Ui.px(16), Ui.px(12), Ui.px(16)) }
        head.addView(IconView(this, "watch", if (up) Ui.GREEN else Ui.TEXT2), Ui.lp(Ui.px(48), Ui.px(48)))
        head.addView(Ui.text(this, if (up) "Watch connected" else "Waiting for the watch", 19f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }, Ui.lp(t = 6))
        head.addView(Ui.text(this, if (up) s!!.link.peer else (s?.status ?: "Starting…"), 13f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, Ui.lp(t = 3))
        col.addView(head, Ui.lp(b = 10))

        val set = Ui.card(this)
        set.addView(Ui.text(this, "Set up this phone", 16f, Ui.TEXT, true), Ui.lp(b = 6))
        fun item(title: String, help: String, on: Boolean?, act: () -> Unit) {
            val r = Ui.row(this).apply { background = Ui.ripple(Ui.CARD2, 12f); setPadding(Ui.px(10), Ui.px(9), Ui.px(10), Ui.px(9)); isClickable = true; setOnClickListener { act() } }
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(Ui.text(this, title, 14.5f, Ui.TEXT, true))
            t.addView(Ui.text(this, help, 12f, Ui.TEXT2), Ui.lp(t = 2))
            r.addView(t, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, r = 8))
            r.addView(Ui.text(this, when (on) { true -> "On"; false -> "Off"; null -> "Open" }, 13f, when (on) { true -> Ui.GREEN; false -> Ui.AMBER; null -> Ui.TEXT2 }, true))
            set.addView(r, Ui.lp(t = 3, b = 3))
        }
        if (Build.VERSION.SDK_INT >= 31) item("Nearby devices", "For the Bluetooth link to the watch", granted(Manifest.permission.BLUETOOTH_CONNECT)) { askPermissions() }
        item("Pair the watch", "Once, in Bluetooth settings", null) { open(Settings.ACTION_BLUETOOTH_SETTINGS) }
        item("Notification access", "Sends notifications and music to the watch", notificationsAllowed()) {
            open(if (Build.VERSION.SDK_INT >= 22) Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS else "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        }
        item("HandyWatch control", "Accessibility: keys, touchpad, typing and opening the camera from the watch", ControlAccess.on()) {
            AlertDialog.Builder(this).setTitle("HandyWatch control")
                .setMessage("In Accessibility, open Installed apps (or Downloaded services), choose HandyWatch control and switch it on. If Android says it's a restricted setting, open App info › ⋮ › Allow restricted settings first.")
                .setPositiveButton("Open Accessibility") { _, _ -> open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
                .setNeutralButton("App info") { _, _ -> appDetails() }
                .setNegativeButton("Cancel", null).show()
        }
        item("Camera", "For the watch's shutter", granted(Manifest.permission.CAMERA)) { askPermissions() }
        item("Locking the phone", "Optional: the watch's Lock phone button", Lock.allowed(this)) {
            if (!Lock.allowed(this)) startActivity(Lock.askIntent(this)) else toast("Allowed (Settings › Security › Device admin apps to remove it)")
        }
        item("Battery", "Unrestricted, and Autostart on Vivo", batteryFree()) { appDetails() }
        item("Share mobile data", "Turn on Bluetooth tethering for the watch", null) { openTethering() }
        col.addView(set, Ui.lp(b = 10))

        val more = Ui.card(this)
        more.addView(Ui.text(this, "More", 16f, Ui.TEXT, true), Ui.lp(b = 8))
        more.addView(Ui.button(this, "Send a test notification", size = 13.5f) {
            if (!up) toast("The watch isn't connected") else { s?.testNotification(); toast("Sent. Check the watch") }
        }, Ui.lp(b = 6))
        more.addView(Ui.button(this, "Choose apps not to send", size = 13.5f) { excludeApps() }, Ui.lp(b = 6))
        more.addView(Ui.button(this, "Use this device as the watch", size = 13.5f) {
            AlertDialog.Builder(this).setTitle("Use as the watch?").setMessage("This device will show another phone's notifications instead of sharing its own.")
                .setPositiveButton("Switch") { _, _ -> setRole(Prefs.ROLE_WATCH) }.setNegativeButton("Cancel", null).show()
        }, Ui.lp(b = 6))
        more.addView(Ui.button(this, "Exit HandyWatch", Ui.CARD2, Ui.RED, 13.5f) { PhoneService.instance?.exitAll(); finishAffinity() }, Ui.lp())
        col.addView(more, Ui.lp(b = 10))
        col.addView(Ui.text(this, "HandyWatch v${Prefs.VERSION} (${Prefs.BUILD_DATE})", 11.5f, Ui.GREY).apply { gravity = Gravity.CENTER }, Ui.lp(t = 4))
    }

    private fun open(action: String) { try { startActivity(Intent(action)) } catch (_: Exception) { toast("Couldn't open that setting") } }

    private fun appDetails() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
        toast("Battery: Unrestricted. Allow Autostart if your phone has it")
    }

    /** Android has no public shortcut to tethering: try the usual screen, then the network settings. */
    private fun openTethering() {
        val tries = listOf(
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity")),
            Intent(Settings.ACTION_WIRELESS_SETTINGS))
        for (i in tries) { try { startActivity(i); toast("Turn on Bluetooth tethering"); return } catch (_: Exception) { } }
        toast("Open Settings and search for Bluetooth tethering")
    }

    private fun excludeApps() {
        val pkgs = Prefs.seen(this).sortedBy { label(it).lowercase() }
        if (pkgs.isEmpty()) { toast("Apps appear here once they've shown a notification"); return }
        val ex = Prefs.excluded(this).toMutableSet()
        AlertDialog.Builder(this).setTitle("Don't send notifications from")
            .setMultiChoiceItems(pkgs.map { label(it) }.toTypedArray(), pkgs.map { ex.contains(it) }.toBooleanArray()) { _, i, on -> if (on) ex.add(pkgs[i]) else ex.remove(pkgs[i]) }
            .setPositiveButton("Save") { _, _ -> Prefs.setExcluded(this, ex) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun label(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
