package net.rathour.handywatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build

/** Every setting, on this device only. */
object Prefs {
    const val VERSION = "0.5"
    const val BUILD_DATE = "14 November 2026"
    const val ROLE_NONE = ""
    const val ROLE_PHONE = "phone"
    const val ROLE_WATCH = "watch"
    const val DEFAULT_BRIDGE = "https://rathour.net/claude/esp/esp_status.php"

    fun p(ctx: Context): SharedPreferences = ctx.getSharedPreferences("handywatch", Context.MODE_PRIVATE)

    fun role(ctx: Context) = p(ctx).getString("role", ROLE_NONE) ?: ROLE_NONE
    fun setRole(ctx: Context, r: String) = p(ctx).edit().putString("role", r).apply()

    // the watch: which phone to connect to
    fun phoneAddr(ctx: Context) = p(ctx).getString("phone_addr", "") ?: ""
    fun phoneName(ctx: Context) = p(ctx).getString("phone_name", "") ?: ""
    fun setPhone(ctx: Context, addr: String, name: String) = p(ctx).edit().putString("phone_addr", addr).putString("phone_name", name).apply()

    // the watch: notifications
    fun buzz(ctx: Context) = p(ctx).getBoolean("buzz", true)
    fun dismissBoth(ctx: Context) = p(ctx).getBoolean("dismiss_both", true)

    // the watch: status dials
    fun bridge(ctx: Context) = (p(ctx).getString("bridge", DEFAULT_BRIDGE) ?: DEFAULT_BRIDGE).ifBlank { DEFAULT_BRIDGE }
    fun readKey(ctx: Context) = p(ctx).getString("read_key", "") ?: ""
    fun refreshSecs(ctx: Context) = p(ctx).getInt("refresh", 10)
    fun dialAlerts(ctx: Context) = p(ctx).getBoolean("dial_alerts", true)
    fun viaPhone(ctx: Context) = p(ctx).getBoolean("via_phone", true)

    // the watch: touchpad speed (0 slow, 1 normal, 2 fast) and the last page shown
    fun padSpeed(ctx: Context) = p(ctx).getInt("pad_speed", 1)
    fun page(ctx: Context) = p(ctx).getInt("page", 0)
    /** "keys": the watch is a Bluetooth keyboard (real keys); "swipes": through the phone's Accessibility. */
    fun keysMode(ctx: Context) = p(ctx).getString("keys_mode", "keys") ?: "keys"
    fun theme(ctx: Context) = p(ctx).getString("theme", "oled") ?: "oled"

    fun set(ctx: Context, key: String, v: Any) {
        val e = p(ctx).edit()
        when (v) {
            is Boolean -> e.putBoolean(key, v)
            is Int -> e.putInt(key, v)
            is String -> e.putString(key, v)
        }
        e.apply()
    }

    // the phone: apps whose notifications aren't sent
    fun seen(ctx: Context): Set<String> = p(ctx).getStringSet("seen", emptySet()) ?: emptySet()
    fun excluded(ctx: Context): Set<String> = p(ctx).getStringSet("excluded", emptySet()) ?: emptySet()
    fun setExcluded(ctx: Context, s: Set<String>) = p(ctx).edit().putStringSet("excluded", HashSet(s)).apply()
    fun remember(ctx: Context, pkg: String) {
        val s = seen(ctx)
        if (!s.contains(pkg)) p(ctx).edit().putStringSet("seen", HashSet(s + pkg)).apply()
    }
}

/** Small helpers shared by both sides. */
object Sys {
    val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

    fun channel(ctx: Context, id: String, name: String, importance: Int) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }

    @Suppress("DEPRECATION")
    fun builder(ctx: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else Notification.Builder(ctx)

    fun openApp(ctx: Context, page: Int = -1): PendingIntent =
        PendingIntent.getActivity(ctx, 100 + page, Intent(ctx, MainActivity::class.java).putExtra("page", page)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PI_FLAGS)

    /** Starts the background service for this device's role (nothing before a role is chosen). */
    fun startService(ctx: Context) {
        val cls = when (Prefs.role(ctx)) {
            Prefs.ROLE_PHONE -> PhoneService::class.java
            Prefs.ROLE_WATCH -> WatchService::class.java
            else -> return
        }
        val i = Intent(ctx, cls)
        try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (_: Exception) { }
    }

    fun stopServices(ctx: Context) {
        ctx.stopService(Intent(ctx, PhoneService::class.java))
        ctx.stopService(Intent(ctx, WatchService::class.java))
    }
}

/** Starts HandyWatch's service after the device restarts or the app is updated. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Prefs.p(ctx).getBoolean("exited", false)) return // Exit was chosen: wait until the app is opened
        Sys.startService(ctx)
    }
}
