package net.rathour.handywatch

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.RingtoneManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The phone's side: listens for the watch over Bluetooth and keeps it up to date with the
 * phone's notifications, what's playing and the battery; carries out the watch's commands
 * (music, volume, find my phone, lock, camera, keys, touchpad, typing); and fetches the status
 * dials for it from the rathour.net bridge.
 */
class PhoneService : Service() {
    companion object {
        @Volatile var instance: PhoneService? = null
        private const val CHANNEL = "handywatch_link"
    }

    lateinit var link: BtLink
    var status = "Starting…"; private set
    var onUi: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var lastBattery = -1
    private var lastCharging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.set(this, "exited", false)
        Sys.channel(this, CHANNEL, "HandyWatch connection", NotificationManager.IMPORTANCE_LOW)
        startFg()
        link = BtLink(true, { "" }, { line -> main.post { onLine(line) } }) { up, s ->
            main.post {
                status = if (up) "Watch connected: $s" else s
                if (up) { liveWanted = false; sendAll(); CameraActivity.current?.sendState() }
                onUi?.invoke()
            }
        }
        link.start()
        attachMusic()
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeWatch)
        if (Build.VERSION.SDK_INT >= 24) runCatching {
            android.service.notification.NotificationListenerService.requestRebind(ComponentName(this, NotifyListener::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "net.rathour.handywatch.EXIT") { exitAll(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        link.stop()
        stopRinging()
        runCatching { unregisterReceiver(battery) }
        runCatching { contentResolver.unregisterContentObserver(volumeWatch) }
        controller?.unregisterCallback(musicCb)
        instance = null
        super.onDestroy()
    }

    private fun startFg() {
        val exit = PendingIntent.getService(this, 1, Intent(this, PhoneService::class.java).setAction("net.rathour.handywatch.EXIT"), Sys.PI_FLAGS)
        @Suppress("DEPRECATION")
        val n = Sys.builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("HandyWatch")
            .setContentText("Sharing with your watch when it's near")
            .setContentIntent(Sys.openApp(this))
            .setOngoing(true)
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
    }

    fun send(o: JSONObject) { if (::link.isInitialized && link.connected) link.send(o.toString()) }
    fun send(t: String, vararg kv: Pair<String, Any?>) {
        val o = JSONObject().put("t", t)
        for ((k, v) in kv) o.put(k, v ?: JSONObject.NULL)
        send(o)
    }

    /** Everything the watch shows, when it connects or asks. */
    private fun sendAll() {
        send("hello", "name" to Build.MODEL, "v" to Prefs.VERSION, "control" to ControlAccess.on(), "lock" to Lock.allowed(this),
            "notify" to (NotifyListener.instance != null))
        sendNotifications()
        sendMusic()
        lastBattery = -1
        sendBattery(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
    }

    // ---- notifications (from NotifyListener)
    fun notificationJson(sbn: StatusBarNotification): JSONObject? {
        val n = sbn.notification ?: return null
        if (sbn.packageName == packageName) return null
        if (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0 && n.category != android.app.Notification.CATEGORY_CALL) return null
        Prefs.remember(this, sbn.packageName)
        if (Prefs.excluded(this).contains(sbn.packageName)) return null
        val ex = n.extras
        val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
        val body = (ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString() ?: ""
        if (title.isBlank() && body.isBlank()) return null
        return JSONObject().put("key", sbn.key).put("app", label(sbn.packageName)).put("pkg", sbn.packageName)
            .put("title", title.take(100)).put("body", body.take(600)).put("when", sbn.postTime)
    }

    fun notificationPosted(sbn: StatusBarNotification) { notificationJson(sbn)?.let { send(it.put("t", "n+")) } }
    fun notificationRemoved(sbn: StatusBarNotification) { send("n-", "key" to sbn.key) }

    private fun sendNotifications() {
        val arr = JSONArray()
        val list = runCatching { NotifyListener.instance?.activeNotifications?.toList() }.getOrNull() ?: emptyList()
        for (sbn in list.sortedByDescending { it.postTime }.take(40)) notificationJson(sbn)?.let { arr.put(it) }
        send("nall", "list" to arr, "access" to (NotifyListener.instance != null))
    }

    fun label(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    // ---- the battery
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { sendBattery(i) }
    }

    private fun sendBattery(i: Intent?) {
        i ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level < 0) -1 else level * 100 / scale
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        if (pct == lastBattery && charging == lastCharging) return
        lastBattery = pct; lastCharging = charging
        send("bat", "level" to pct, "charging" to charging)
    }

    // ---- music and volume (music needs notification access)
    fun attachMusic() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = ComponentName(this, NotifyListener::class.java)
            val pick = { list: List<MediaController>? ->
                controller?.unregisterCallback(musicCb)
                controller = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
                controller?.registerCallback(musicCb, main)
                sendMusic()
            }
            pick(msm.getActiveSessions(comp))
            msm.addOnActiveSessionsChangedListener({ pick(it) }, comp, main)
        } catch (_: SecurityException) { /* notification access not given yet */ } catch (_: Exception) { }
    }

    private val musicCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { sendMusic() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { sendMusic() }
        override fun onSessionDestroyed() { controller = null; sendMusic() }
    }

    private val volumeWatch = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { sendMusic() }
    }

    private var lastMusic = ""
    fun sendMusic() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val c = controller
        val md = c?.metadata
        val o = JSONObject().put("t", "media").put("has", c != null)
            .put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
            .put("artist", md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
            .put("app", c?.packageName?.let { label(it) } ?: "")
            .put("playing", c?.playbackState?.state == PlaybackState.STATE_PLAYING)
            .put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        val s = o.toString()
        if (s == lastMusic) return
        lastMusic = s
        send(o)
    }

    private fun media(a: String) {
        val c = controller
        val tc = c?.transportControls
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (a) {
            "playpause" -> if (c == null) toWatch("Nothing is playing on the phone")
                else if (c.playbackState?.state == PlaybackState.STATE_PLAYING) tc?.pause() else tc?.play()
            "next" -> tc?.skipToNext()
            "prev" -> tc?.skipToPrevious()
            "volup" -> volume(true)
            "voldown" -> volume(false)
        }
        main.postDelayed({ sendMusic() }, 300)
    }

    /**
     * One step of volume, tried three ways until the level moves (some phones ignore one way from an
     * app in the background): the playing app's own volume, Android's music volume, then a volume
     * key press sent to the music. The watch's volume bar then shows the real level.
     */
    private fun volume(up: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if ((up && before >= max) || (!up && before <= 0)) { lastMusic = ""; sendMusic(); return }
        val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val moved = { am.getStreamVolume(AudioManager.STREAM_MUSIC) != before }
        runCatching { controller?.adjustVolume(dir, AudioManager.FLAG_SHOW_UI) }
        main.postDelayed({
            if (!moved()) runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
            main.postDelayed({
                if (!moved()) runCatching {
                    val code = if (up) android.view.KeyEvent.KEYCODE_VOLUME_UP else android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
                }
                main.postDelayed({
                    lastMusic = ""
                    sendMusic()
                    if (!moved()) toWatch("The phone didn't change its volume. Try Settings › Keys › Real keys")
                }, 200)
            }, 150)
        }, 150)
    }

    fun toWatch(text: String) = send("toast", "text" to text)

    // ---- from the watch
    private fun onLine(line: String) {
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return
        val ctl = ControlAccess.instance
        when (o.optString("cmd")) {
            "hello" -> sendAll()
            "dismiss" -> { val k = o.optString("key"); if (k.isNotEmpty()) runCatching { NotifyListener.instance?.cancelNotification(k) } }
            "dismissall" -> runCatching { NotifyListener.instance?.cancelAllNotifications() }
            "media" -> media(o.optString("a"))
            "ring" -> ring(o.optBoolean("on"))
            "lock" -> { Lock.toggle(this) }
            "cam" -> camera(o)
            "key" -> if (o.optString("k") == "play") media("playpause")
                else if (o.optString("k") == "volup" || o.optString("k") == "voldown") media(o.optString("k"))
                else if (ctl == null) needControl() else ctl.key(o.optString("k"))
            "pad" -> if (ctl == null) needControl() else ctl.showPointer(o.optBoolean("on"))
            "move" -> ctl?.move(o.optDouble("dx", 0.0).toFloat(), o.optDouble("dy", 0.0).toFloat(), o.optInt("speed", 1))
            "tap" -> if (ctl == null) needControl() else ctl.tap(false)
            "hold" -> if (ctl == null) needControl() else ctl.tap(true)
            "scroll" -> ctl?.scroll(o.optDouble("dx", 0.0).toFloat(), o.optDouble("dy", 0.0).toFloat())
            "type" -> if (ctl == null) needControl() else ctl.type(o.optString("text"))
            "bksp" -> ctl?.backspace()
            "enter" -> ctl?.enter()
            "dials" -> dials(o.optInt("id"), o.optString("url"), o.optString("key"))
        }
    }

    private fun needControl() = toWatch("On the phone, turn on HandyWatch control (Accessibility)")

    // ---- find my phone: the ringtone as an alarm (heard in silent and vibrate mode), at full alarm volume, with vibration
    private var player: android.media.MediaPlayer? = null
    private var savedAlarmVol = -1

    private fun ring(on: Boolean) {
        stopRinging()
        if (on) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching {
                savedAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }
            val uris = listOfNotNull(
                RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE),
                RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                android.provider.Settings.System.DEFAULT_RINGTONE_URI)
            for (u in uris) {
                val mp = android.media.MediaPlayer()
                try {
                    mp.setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    mp.setDataSource(this, u)
                    mp.isLooping = true
                    mp.prepare()
                    mp.start()
                    player = mp
                    break
                } catch (_: Exception) { runCatching { mp.release() } }
            }
            vibrate(true)
            main.postDelayed(stopLater, 60_000) // stops by itself after a minute
        }
        send("ring", "on" to (player != null))
        if (on && player == null) toWatch("The phone couldn't play its ringtone")
    }

    private val stopLater = Runnable { stopRinging(); send("ring", "on" to false) }

    private fun stopRinging() {
        main.removeCallbacks(stopLater)
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        vibrate(false)
        if (savedAlarmVol >= 0) {
            runCatching { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVol, 0) }
            savedAlarmVol = -1
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(on: Boolean) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (!on) { v.cancel(); return }
        val pattern = longArrayOf(0, 600, 400)
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0)) else v.vibrate(pattern, 0)
    }

    // ---- the camera: the phone's own camera screen, opened from the watch
    private fun camera(o: JSONObject) {
        val cur = CameraActivity.current
        val delay = o.optInt("delay", 0)
        when (o.optString("a")) {
            "open" -> if (cur == null) openCamera(false, 0, false) else cur.sendState()
            "close" -> cur?.finish()
            "shoot" -> if (cur != null) cur.shoot(delay, o.optBoolean("video")) else openCamera(true, delay, o.optBoolean("video"))
            "lens" -> cur?.switchLens()
            "blank" -> cur?.setBlank(o.optBoolean("on"))
            "flash" -> cur?.cycleFlash()
            "live" -> { liveWanted = o.optBoolean("on"); frameBusy = false }
            "ack" -> frameBusy = false
        }
    }

    // ---- the live preview: one small JPEG at a time, the next only after the watch has shown the last
    @Volatile var liveWanted = false
    @Volatile private var frameBusy = false
    @Volatile private var frameAt = 0L
    fun wantsFrame(): Boolean {
        if (!liveWanted || !link.connected) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (frameBusy && now - frameAt < 1500) return false // an answer was lost: send again after 1.5 s
        return now - frameAt >= 90
    }
    fun sendFrame(b64: String, rot: Int) {
        frameBusy = true
        frameAt = android.os.SystemClock.elapsedRealtime()
        send("frame", "jpg" to b64, "rot" to rot)
    }

    /** Can Android let HandyWatch open its camera screen from the background? (HandyWatch control, or "Display over other apps".) */
    fun canOpenScreens() = ControlAccess.instance != null || Build.VERSION.SDK_INT < 29 ||
        (Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this))

    private fun openCamera(shoot: Boolean, delay: Int, video: Boolean) {
        val i = Intent(this, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("shoot", shoot).putExtra("delay", delay).putExtra("video", video)
        val from: Context = ControlAccess.instance ?: this
        try { from.startActivity(i) } catch (_: Exception) { }
        main.postDelayed({
            if (CameraActivity.current != null) return@postDelayed
            // Android blocked it: offer a notification to tap instead, and say why
            Sys.channel(this, "handywatch_camera", "Camera from the watch", NotificationManager.IMPORTANCE_HIGH)
            @Suppress("DEPRECATION")
            val n = Sys.builder(this, "handywatch_camera")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Tap to open the camera")
                .setContentText("Your watch asked for the camera. Turn on HandyWatch control to skip this step.")
                .setContentIntent(PendingIntent.getActivity(this, 7, i, Sys.PI_FLAGS))
                .setAutoCancel(true)
                .setPriority(android.app.Notification.PRIORITY_HIGH)
                .build()
            runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(7, n) }
            toWatch(if (canOpenScreens()) "The phone's camera screen didn't open. Tap the notification on the phone"
                else "Android blocked the camera screen. Tap the notification on the phone, or turn on HandyWatch control")
        }, 2500)
    }

    fun cameraState(o: JSONObject) = send(o.put("t", "cam"))
    fun sendShot(b64: String) = send("shot", "jpg" to b64)

    // ---- status dials: fetched here and passed to the watch (so it works without the watch's own internet)
    private fun dials(id: Int, url: String, key: String) {
        Thread {
            var code = 0
            var body = ""
            try {
                if (!url.startsWith("https://") && !url.startsWith("http://")) throw IllegalArgumentException()
                val full = url + (if (url.contains('?')) "&" else "?") + "key=" + URLEncoder.encode(key, "UTF-8")
                val c = URL(full).openConnection() as HttpURLConnection
                c.connectTimeout = 12000; c.readTimeout = 12000
                c.setRequestProperty("User-Agent", "HandyWatch/" + Prefs.VERSION)
                code = c.responseCode
                val s = if (code in 200..299) c.inputStream else c.errorStream
                body = s?.use { it.readBytes().take(65536).toByteArray().toString(Charsets.UTF_8) } ?: ""
                c.disconnect()
            } catch (_: Exception) { code = 0 }
            main.post { send("dials", "id" to id, "code" to code, "body" to body) }
        }.start()
    }

    /** A test notification straight to the watch (not through Android's notification listener). */
    fun testNotification() {
        send(JSONObject().put("t", "n+").put("key", "handywatch-test").put("app", "HandyWatch").put("pkg", packageName)
            .put("title", "Test notification").put("body", "If you can read this on the watch, the link works.").put("when", System.currentTimeMillis()))
    }
}
