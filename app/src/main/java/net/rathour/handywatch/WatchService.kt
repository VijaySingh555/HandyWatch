package net.rathour.handywatch

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The watch's side: keeps the Bluetooth link to the phone, holds what the phone last sent
 * (notifications, music, battery, camera), shows the phone's notifications as the watch's own
 * (so they buzz), and fetches the status dials (through the phone, or directly).
 */
class WatchService : Service() {
    companion object {
        @Volatile var instance: WatchService? = null
        private const val CH_LINK = "handywatch_link"
        private const val CH_ALERT = "handywatch_alert"
        private const val CH_QUIET = "handywatch_quiet"
        private const val CH_DIALS = "handywatch_dials"
        fun nid(key: String) = (key.hashCode() and 0x3FFFFFFF) + 1000
    }

    lateinit var link: BtLink
    var status = "Starting…"; private set
    val connected get() = ::link.isInitialized && link.connected
    var phone = ""; private set
    val notifs = LinkedHashMap<String, JSONObject>()
    var media: JSONObject? = null; private set
    var battery = -1; private set
    var charging = false; private set
    var control = false; private set
    var lockOk = false; private set
    var notifyAccess = true; private set
    var camOpen = false; private set
    var camCount = 0; private set
    var camRec = false; private set
    var camSecs = 0L; private set
    var camBlank = false; private set
    var camFront = false; private set
    var camFlash = "off"; private set
    var camHasFlash = false; private set
    var frame: Bitmap? = null; private set
    @Volatile private var decoding = false
    var ringing = false; private set
    var shot: Bitmap? = null; private set
    var shotAt = 0L; private set
    /** The screen listens here: what changed ("link", "notifs", "media", "bat", "cam", "shot", "ring") or "toast:<text>". */
    var onChange: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val nm by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private var reqId = 0
    private val pending = HashMap<Int, (Int, String) -> Unit>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.set(this, "exited", false)
        Sys.channel(this, CH_LINK, "HandyWatch connection", NotificationManager.IMPORTANCE_MIN)
        Sys.channel(this, CH_ALERT, "Phone notifications", NotificationManager.IMPORTANCE_HIGH)
        Sys.channel(this, CH_QUIET, "Phone notifications (silent)", NotificationManager.IMPORTANCE_LOW)
        Sys.channel(this, CH_DIALS, "Status dials", NotificationManager.IMPORTANCE_DEFAULT)
        startFg()
        link = BtLink(false, { Prefs.phoneAddr(this) }, { line -> main.post { onLine(line) } }) { up, s ->
            main.post {
                status = if (up) "Connected" else s
                if (up) { phone = s; link.send("{\"cmd\":\"hello\"}") }
                else { pending.values.toList().forEach { it(0, "") }; pending.clear() }
                startFg()
                changed("link")
            }
        }
        link.start()
        hid = HidKeys(this) { main.post { changed("hid") } }
        if (Prefs.keysMode(this) == "keys") hid.enable(true)
    }

    // ---- the watch as a Bluetooth keyboard (real keys), or swipes through the phone's Accessibility
    lateinit var hid: HidKeys
    private val realKeys get() = Prefs.keysMode(this) == "keys" && hid.connected

    fun setKeysMode(mode: String) { Prefs.set(this, "keys_mode", mode); hid.enable(mode == "keys"); changed("hid") }

    /** One key from the Keys tab: up, down, left, right, play, back, home, volup, voldown. */
    fun pressKey(k: String) {
        if (realKeys) when (k) {
            "up" -> return hid.key(HidKeys.K_UP)
            "down" -> return hid.key(HidKeys.K_DOWN)
            "left" -> return hid.key(HidKeys.K_LEFT)
            "right" -> return hid.key(HidKeys.K_RIGHT)
            "play" -> return hid.consumer(HidKeys.PLAY_PAUSE)
            "back" -> return hid.consumer(HidKeys.AC_BACK)
            "home" -> return hid.consumer(HidKeys.AC_HOME)
            "volup" -> return hid.consumer(HidKeys.VOL_UP)
            "voldown" -> return hid.consumer(HidKeys.VOL_DOWN)
        }
        cmd("key", "k" to k)
    }

    /** Music page buttons: real media and volume keys when the keyboard is connected. */
    fun mediaKey(a: String) {
        if (realKeys) when (a) {
            "volup" -> return hid.consumer(HidKeys.VOL_UP)
            "voldown" -> return hid.consumer(HidKeys.VOL_DOWN)
        }
        cmd("media", "a" to a)
    }

    /** Typing: as key presses when the keyboard is connected (if every letter has a key), otherwise through Accessibility. */
    fun type(text: String): Boolean = if (realKeys && hid.type(text)) true else cmd("type", "text" to text)
    fun backspace() { if (realKeys) hid.key(HidKeys.K_BKSP) else cmd("bksp") }
    fun enter() { if (realKeys) hid.key(HidKeys.K_ENTER) else cmd("enter") }

    fun keysStatus(): String = when {
        Prefs.keysMode(this) != "keys" -> "Arrows are swipes (needs HandyWatch control on the phone)"
        !hid.supported() -> "This watch's Android is too old for keyboard mode: using swipes"
        hid.state == "unavailable" -> "This watch can't act as a Bluetooth keyboard: using swipes"
        hid.state == "connected" -> "Keyboard connected to ${hid.hostName}"
        hid.state == "checking" -> "Checking this watch…"
        hid.state == "connecting" -> "Connecting the keyboard… If it never connects, unpair and pair the phone again"
        Prefs.phoneAddr(this).isBlank() -> "Choose your phone first"
        else -> "Keyboard off (is Bluetooth on?): using swipes"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "net.rathour.handywatch.EXIT" -> { exitAll(); return START_NOT_STICKY }
            "net.rathour.handywatch.DISMISSED" -> intent.getStringExtra("key")?.let { dismissedHere(it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        link.stop()
        hid.stop()
        for (k in notifs.keys) nm.cancel(nid(k))
        instance = null
        super.onDestroy()
    }

    private fun startFg() {
        val exit = PendingIntent.getService(this, 1, Intent(this, WatchService::class.java).setAction("net.rathour.handywatch.EXIT"), Sys.PI_FLAGS)
        @Suppress("DEPRECATION")
        val n = Sys.builder(this, CH_LINK)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("HandyWatch")
            .setContentText(if (connected) "Connected to $phone" else status)
            .setContentIntent(Sys.openApp(this))
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .addAction(0, "Exit", exit)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            try { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) } catch (_: Exception) { runCatching { startForeground(1, n) } }
        } else startForeground(1, n)
    }

    fun exitAll() {
        Prefs.set(this, "exited", true)
        stopForeground(true)
        stopSelf()
        changed("exit")
    }

    private fun changed(what: String) { onChange?.invoke(what) }

    // ---- to the phone
    fun cmd(c: String, vararg kv: Pair<String, Any?>): Boolean {
        if (!connected) { changed("toast:The phone isn't connected"); return false }
        val o = JSONObject().put("cmd", c)
        for ((k, v) in kv) o.put(k, v ?: JSONObject.NULL)
        link.send(o.toString())
        return true
    }

    fun reconnect() { link.poke(); if (Prefs.keysMode(this) == "keys") hid.enable(true) }

    // ---- from the phone
    private fun onLine(line: String) {
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (o.optString("t")) {
            "hello" -> {
                phone = o.optString("name", phone)
                control = o.optBoolean("control"); lockOk = o.optBoolean("lock"); notifyAccess = o.optBoolean("notify", true)
                startFg(); changed("link")
            }
            "ctl" -> { control = o.optBoolean("on"); changed("link") }
            "nall" -> {
                notifyAccess = o.optBoolean("access", true)
                val arr = o.optJSONArray("list")
                val fresh = LinkedHashMap<String, JSONObject>()
                if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { fresh[it.optString("key")] = it }
                for (k in notifs.keys) if (!fresh.containsKey(k)) nm.cancel(nid(k))
                for ((k, n) in fresh) if (!notifs.containsKey(k)) postLocal(n, false)
                notifs.clear(); notifs.putAll(fresh)
                changed("notifs")
            }
            "n+" -> {
                val k = o.optString("key")
                val isNew = !notifs.containsKey(k)
                notifs.remove(k)
                val copy = LinkedHashMap(notifs)
                notifs.clear(); notifs[k] = o; notifs.putAll(copy) // newest first
                while (notifs.size > 60) notifs.remove(notifs.keys.last())
                postLocal(o, isNew && Prefs.buzz(this))
                changed("notifs")
            }
            "n-" -> { val k = o.optString("key"); notifs.remove(k); nm.cancel(nid(k)); changed("notifs") }
            "media" -> { media = o; changed("media") }
            "bat" -> { battery = o.optInt("level", -1); charging = o.optBoolean("charging"); changed("bat") }
            "cam" -> {
                camOpen = o.optBoolean("open"); camCount = o.optInt("count"); camRec = o.optBoolean("rec"); camSecs = o.optLong("secs")
                camBlank = o.optBoolean("blank"); camFront = o.optBoolean("front")
                camFlash = o.optString("flash", "off"); camHasFlash = o.optBoolean("hasFlash")
                if (!camOpen) frame = null
                changed("cam")
            }
            "frame" -> showFrame(o.optString("jpg"), o.optInt("rot"))
            "snap" -> { buzz(if (o.optString("kind") == "recstop") 2 else 1); changed("snap:" + o.optString("kind")) }
            "shot" -> {
                val b = runCatching { Base64.decode(o.optString("jpg"), Base64.DEFAULT) }.getOrNull()
                shot = b?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                shotAt = System.currentTimeMillis()
                changed("shot")
            }
            "ring" -> { ringing = o.optBoolean("on"); changed("ring") }
            "toast" -> changed("toast:" + o.optString("text"))
            "dials" -> pending.remove(o.optInt("id"))?.invoke(o.optInt("code"), o.optString("body"))
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz(times: Int) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        val pattern = if (times == 2) longArrayOf(0, 90, 120, 90) else longArrayOf(0, 120)
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1)) else v.vibrate(pattern, -1)
    }

    // ---- the live preview: decode off the main thread, show it, then ask for the next
    private val decoder = java.util.concurrent.Executors.newSingleThreadExecutor()
    private fun showFrame(b64: String, rot: Int) {
        if (decoding) return
        decoding = true
        decoder.execute {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (b != null && rot != 0) {
                    val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()); if (camFront) postScale(-1f, 1f) }
                    b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
                }
                main.post { if (b != null) { frame = b; changed("frame") }; decoding = false; if (connected) link.send("{\"cmd\":\"cam\",\"a\":\"ack\"}") }
            } catch (_: Exception) { decoding = false; main.post { if (connected) link.send("{\"cmd\":\"cam\",\"a\":\"ack\"}") } }
        }
    }

    /** The watch's camera page is showing (and the phone's camera is open): ask for frames. */
    fun live(on: Boolean) { if (connected) link.send(JSONObject().put("cmd", "cam").put("a", "live").put("on", on).toString()) }

    // ---- notifications on the watch
    private fun postLocal(n: JSONObject, alert: Boolean) {
        val key = n.optString("key")
        val del = PendingIntent.getBroadcast(this, nid(key), Intent(this, DismissReceiver::class.java).putExtra("key", key), Sys.PI_FLAGS)
        val title = n.optString("title")
        val body = n.optString("body")
        @Suppress("DEPRECATION")
        val b = Sys.builder(this, if (alert) CH_ALERT else CH_QUIET)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title.ifBlank { n.optString("app") })
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSubText(n.optString("app"))
            .setWhen(n.optLong("when", System.currentTimeMillis()))
            .setShowWhen(true)
            .setContentIntent(Sys.openApp(this, 0))
            .setDeleteIntent(del)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup("phone")
        if (Build.VERSION.SDK_INT < 26) b.setPriority(if (alert) Notification.PRIORITY_HIGH else Notification.PRIORITY_LOW).setDefaults(if (alert) Notification.DEFAULT_ALL else 0)
        runCatching { nm.notify(nid(key), b.build()) }
    }

    /** Swiped away on the watch (or in the app): gone here, and on the phone too if that's chosen. */
    fun dismissedHere(key: String) {
        notifs.remove(key)
        nm.cancel(nid(key))
        if (Prefs.dismissBoth(this) && connected) link.send(JSONObject().put("cmd", "dismiss").put("key", key).toString())
        changed("notifs")
    }

    fun dismissAll() {
        for (k in notifs.keys) nm.cancel(nid(k))
        notifs.clear()
        if (Prefs.dismissBoth(this) && connected) link.send("{\"cmd\":\"dismissall\"}")
        changed("notifs")
    }

    // ---- status dials
    /** Fetches esp_status.php: through the phone when connected (and chosen), otherwise with the watch's own internet. */
    fun fetchDials(done: (Int, String) -> Unit) {
        val url = Prefs.bridge(this)
        val key = Prefs.readKey(this)
        if (connected && Prefs.viaPhone(this)) {
            val id = ++reqId
            pending[id] = done
            link.send(JSONObject().put("cmd", "dials").put("id", id).put("url", url).put("key", key).toString())
            main.postDelayed({ pending.remove(id)?.invoke(0, "") }, 20000)
            return
        }
        Thread {
            var code = 0
            var body = ""
            try {
                val c = URL(url + (if (url.contains('?')) "&" else "?") + "key=" + URLEncoder.encode(key, "UTF-8")).openConnection() as HttpURLConnection
                c.connectTimeout = 12000; c.readTimeout = 12000
                c.setRequestProperty("User-Agent", "HandyWatch/" + Prefs.VERSION)
                code = c.responseCode
                val s = if (code in 200..299) c.inputStream else c.errorStream
                body = s?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                c.disconnect()
            } catch (_: Exception) { code = 0 }
            main.post { done(code, body) }
        }.start()
    }

    fun dialAlert(title: String, text: String, id: Int) {
        @Suppress("DEPRECATION")
        val b = Sys.builder(this, CH_DIALS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(Sys.openApp(this, 3))
            .setAutoCancel(true)
        runCatching { nm.notify(500 + (id and 0xFF), b.build()) }
    }

    /** Is the watch online through the phone's Bluetooth tethering? */
    fun internet(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= 23) {
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "Offline"
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Online through the phone (Bluetooth)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Online by Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Online by mobile data"
                else -> "Online"
            }
        }
        @Suppress("DEPRECATION")
        val ni = cm.activeNetworkInfo ?: return "Offline"
        @Suppress("DEPRECATION")
        return if (ni.type == ConnectivityManager.TYPE_BLUETOOTH) "Online through the phone (Bluetooth)" else if (ni.isConnected) "Online" else "Offline"
    }
}

/** A phone notification swiped away on the watch. */
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        WatchService.instance?.dismissedHere(key)
    }
}
