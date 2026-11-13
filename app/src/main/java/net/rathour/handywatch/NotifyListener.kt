package net.rathour.handywatch

import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The phone: hands its notifications to PhoneService, which sends them to the watch (every app
 * except those excluded). Removing one on the phone removes it on the watch, and the watch can
 * dismiss one here. Also gives HandyWatch the right to see and control what's playing.
 */
class NotifyListener : NotificationListenerService() {
    companion object { @Volatile var instance: NotifyListener? = null }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        PhoneService.instance?.attachMusic()
    }

    override fun onListenerDisconnected() { // Android drops listeners after updates: ask to be rebound
        instance = null
        super.onListenerDisconnected()
        if (Build.VERSION.SDK_INT >= 24) requestRebind(ComponentName(this, NotifyListener::class.java))
    }

    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) { PhoneService.instance?.notificationPosted(sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { PhoneService.instance?.notificationRemoved(sbn) }
}
