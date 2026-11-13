package net.rathour.handywatch

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager

/** Lets HandyWatch lock the phone (Android's "device admin"; only the lock-screen permission). */
class AdminReceiver : DeviceAdminReceiver()

/** The phone's screen from the watch: locked if it's on, woken if it's off (it stays locked). */
object Lock {
    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    fun allowed(ctx: Context) = (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(admin(ctx))

    fun toggle(ctx: Context) {
        val black = BlackoutActivity.current
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        when {
            black != null -> black.finish()
            !pm.isInteractive -> {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "handywatch:wake")
                wl.acquire(3000)
            }
            else -> lock(ctx)
        }
    }

    fun lock(ctx: Context) {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(admin(ctx))) dpm.lockNow()
        else try {
            val from: Context = ControlAccess.instance ?: ctx
            from.startActivity(Intent(ctx, BlackoutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    fun askIntent(ctx: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(ctx))
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets your watch switch the phone's screen off (lock it).")
}

/** A black screen at the lowest brightness (when locking isn't allowed). Tap to come back. */
class BlackoutActivity : Activity() {
    companion object { @Volatile var current: BlackoutActivity? = null }
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }
    override fun onPause() { super.onPause(); finish() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        val v = View(this).apply { setBackgroundColor(Color.BLACK); setOnClickListener { finish() } }
        setContentView(v)
        window.attributes = window.attributes.apply { screenBrightness = 0.0f }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        v.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}
