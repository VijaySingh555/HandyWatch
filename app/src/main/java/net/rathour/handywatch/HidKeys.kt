package net.rathour.handywatch

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * The watch as a Bluetooth keyboard for the phone (Android 9 and later, if the watch's Bluetooth
 * includes the keyboard profile): real arrow keys, volume and media keys, Back and Home, and
 * typing, as HandyDash does. The phone needs no app or Accessibility for these.
 */
@SuppressLint("MissingPermission")
class HidKeys(private val ctx: Context, private val onState: () -> Unit) {
    companion object {
        // report 1: a keyboard (modifier byte, reserved byte, six keys); report 2: consumer keys (one 16-bit usage)
        private val DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
            0xC0.toByte(),
            0x05, 0x0C, 0x09, 0x01, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02,
            0x15, 0x00, 0x26, 0xFF.toByte(), 0x03, 0x19, 0x00, 0x2A, 0xFF.toByte(), 0x03, 0x75, 0x10, 0x95.toByte(), 0x01, 0x81.toByte(), 0x00,
            0xC0.toByte())

        // consumer usages
        const val VOL_UP = 0x00E9
        const val VOL_DOWN = 0x00EA
        const val PLAY_PAUSE = 0x00CD
        const val NEXT = 0x00B5
        const val PREV = 0x00B6
        const val AC_BACK = 0x0224
        const val AC_HOME = 0x0223
        // keyboard usages
        const val K_RIGHT = 0x4F
        const val K_LEFT = 0x50
        const val K_DOWN = 0x51
        const val K_UP = 0x52
        const val K_ENTER = 0x28
        const val K_BKSP = 0x2A
        private const val SHIFT = 0x02

        /** A character as (modifier, key) on a US/UK-style layout, or null if it can't be typed this way. */
        fun keyFor(ch: Char): Pair<Int, Int>? {
            return when (ch) {
                in 'a'..'z' -> 0 to (0x04 + (ch - 'a'))
                in 'A'..'Z' -> SHIFT to (0x04 + (ch - 'A'))
                in '1'..'9' -> 0 to (0x1E + (ch - '1'))
                '0' -> 0 to 0x27
                ' ' -> 0 to 0x2C
                '\n' -> 0 to K_ENTER
                '-' -> 0 to 0x2D; '_' -> SHIFT to 0x2D
                '=' -> 0 to 0x2E; '+' -> SHIFT to 0x2E
                '[' -> 0 to 0x2F; '{' -> SHIFT to 0x2F
                ']' -> 0 to 0x30; '}' -> SHIFT to 0x30
                '\\' -> 0 to 0x31; '|' -> SHIFT to 0x31
                ';' -> 0 to 0x33; ':' -> SHIFT to 0x33
                '\'' -> 0 to 0x34; '"' -> SHIFT to 0x34
                '`' -> 0 to 0x35; '~' -> SHIFT to 0x35
                ',' -> 0 to 0x36; '<' -> SHIFT to 0x36
                '.' -> 0 to 0x37; '>' -> SHIFT to 0x37
                '/' -> 0 to 0x38; '?' -> SHIFT to 0x38
                '!' -> SHIFT to 0x1E; '@' -> SHIFT to 0x1F; '#' -> SHIFT to 0x20; '$' -> SHIFT to 0x21; '%' -> SHIFT to 0x22
                '^' -> SHIFT to 0x23; '&' -> SHIFT to 0x24; '*' -> SHIFT to 0x25; '(' -> SHIFT to 0x26; ')' -> SHIFT to 0x27
                else -> null
            }
        }
    }

    /** "checking", "unavailable" (this watch can't), "off", "connecting", "connected". */
    @Volatile var state = "off"; private set
    var hostName = ""; private set
    private var hid: Any? = null          // BluetoothHidDevice (kept as Any so Android 8 never loads the class)
    private var host: BluetoothDevice? = null
    private var registered = false
    private val main = Handler(Looper.getMainLooper())
    private val queue = Executors.newSingleThreadExecutor()

    val connected get() = state == "connected"

    fun supported() = Build.VERSION.SDK_INT >= 28

    /** Switch the keyboard on (and connect to the phone) or off. */
    fun enable(on: Boolean) {
        if (!on) { stop(); state = "off"; onState(); return }
        if (!supported()) { state = "unavailable"; onState(); return }
        if (hid != null) { connect(); return }
        start28()
    }

    @TargetApi(28)
    private fun start28() {
        val ad = BluetoothAdapter.getDefaultAdapter()
        if (ad == null || !ad.isEnabled) { state = "off"; onState(); return }
        state = "checking"; onState()
        val ok = try {
            ad.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.HID_DEVICE) return
                    hid = proxy
                    register28(proxy as BluetoothHidDevice)
                }
                override fun onServiceDisconnected(profile: Int) { hid = null; registered = false; if (state != "unavailable") state = "off"; onState() }
            }, BluetoothProfile.HID_DEVICE)
        } catch (_: Exception) { false }
        if (!ok) { state = "unavailable"; onState(); return }
        // no answer within 6 s: this watch's Bluetooth has no keyboard profile
        main.postDelayed({ if (hid == null && state == "checking") { state = "unavailable"; onState() } }, 6000)
    }

    @TargetApi(28)
    private fun register28(h: BluetoothHidDevice) {
        val sdp = BluetoothHidDeviceAppSdpSettings("HandyWatch", "HandyWatch keys", "rathour.net", BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR)
        val cb = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, reg: Boolean) {
                registered = reg
                main.post { if (reg) connect() else { if (state != "unavailable") state = "off"; onState() } }
            }
            override fun onConnectionStateChanged(device: BluetoothDevice, st: Int) {
                main.post {
                    when (st) {
                        BluetoothProfile.STATE_CONNECTED -> { host = device; hostName = device.name ?: device.address; state = "connected" }
                        BluetoothProfile.STATE_CONNECTING -> state = "connecting"
                        else -> { if (state != "unavailable") state = if (registered) "connecting" else "off"; if (registered) main.postDelayed({ connect() }, 15000) }
                    }
                    onState()
                }
            }
        }
        val ok = try { h.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), cb) } catch (_: Exception) { false }
        if (!ok) { state = "unavailable"; onState() }
    }

    /** Connect to the phone chosen in Settings. */
    fun connect() {
        if (Build.VERSION.SDK_INT < 28 || !registered || connected) return
        val h = hid as? BluetoothHidDevice ?: return
        val addr = Prefs.phoneAddr(ctx)
        if (addr.isBlank()) return
        try {
            val dev = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(addr)
            state = "connecting"; onState()
            h.connect(dev)
        } catch (_: Exception) { }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= 28) {
            val h = hid as? BluetoothHidDevice
            runCatching { host?.let { h?.disconnect(it) } }
            runCatching { h?.unregisterApp() }
            runCatching { BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) }
        }
        hid = null; host = null; registered = false
    }

    private fun report(id: Int, data: ByteArray) {
        if (Build.VERSION.SDK_INT < 28) return
        val h = hid as? BluetoothHidDevice ?: return
        val d = host ?: return
        runCatching { h.sendReport(d, id, data) }
    }

    /** Press and release a consumer key (volume, play/pause, Back, Home...). */
    fun consumer(usage: Int) {
        if (!connected) return
        queue.execute {
            report(2, byteArrayOf((usage and 0xFF).toByte(), (usage shr 8 and 0xFF).toByte()))
            Thread.sleep(30)
            report(2, byteArrayOf(0, 0))
            Thread.sleep(15)
        }
    }

    /** Press and release a keyboard key. */
    fun key(code: Int, mod: Int = 0) {
        if (!connected) return
        queue.execute { press(code, mod) }
    }

    private fun press(code: Int, mod: Int) {
        report(1, byteArrayOf(mod.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0))
        Thread.sleep(18)
        report(1, ByteArray(8))
        Thread.sleep(12)
    }

    /** Types text if every character has a key; returns false (and types nothing) otherwise. */
    fun type(text: String): Boolean {
        if (!connected) return false
        val keys = text.map { keyFor(it) ?: return false }
        queue.execute { for ((mod, k) in keys) press(k, mod) }
        return true
    }
}
