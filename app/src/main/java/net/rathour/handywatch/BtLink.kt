package net.rathour.handywatch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The link between the phone and the watch: one classic Bluetooth (RFCOMM) connection between
 * paired devices, carrying one JSON message per line. The phone listens; the watch connects and
 * keeps trying, more slowly the longer the phone is away. An idle link costs very little power.
 */
@SuppressLint("MissingPermission")
class BtLink(
    private val server: Boolean,
    private val target: () -> String,            // the watch: the phone's Bluetooth address
    private val onLine: (String) -> Unit,
    private val onState: (Boolean, String) -> Unit // connected?, and a short status for the screen
) {
    companion object {
        val SERVICE: UUID = UUID.fromString("7d3f6a10-3b8e-4c1d-9a52-6e0b4f1c2d77")
        const val NAME = "HandyWatch"
    }

    @Volatile var running = false; private set
    @Volatile var connected = false; private set
    @Volatile var peer = ""; private set
    @Volatile private var sock: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var listen: BluetoothServerSocket? = null
    private val writer = Executors.newSingleThreadExecutor()
    private val wake = Object()
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ if (server) serve() else dial() }, "handywatch-link").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { listen?.close() }
        runCatching { sock?.close() }
        poke()
        writer.shutdownNow()
    }

    /** The watch: try to connect now instead of waiting. */
    fun poke() { synchronized(wake) { wake.notifyAll() } }

    fun send(line: String) {
        if (!running) return
        writer.execute {
            val o = out ?: return@execute
            try {
                o.write((line.replace('\n', ' ') + "\n").toByteArray(Charsets.UTF_8))
                o.flush()
            } catch (_: Exception) { runCatching { sock?.close() } }
        }
    }

    private fun pause(ms: Long) { synchronized(wake) { if (running) runCatching { wake.wait(ms) } } }

    private fun adapter(): BluetoothAdapter? {
        val a = BluetoothAdapter.getDefaultAdapter()
        if (a == null) { onState(false, "This device has no Bluetooth"); return null }
        if (!a.isEnabled) { onState(false, "Bluetooth is off"); return null }
        return a
    }

    /** The phone: wait for the watch. A new connection replaces the old one. */
    private fun serve() {
        while (running) {
            val a = adapter()
            if (a == null) { pause(5000); continue }
            try {
                onState(false, "Waiting for the watch")
                val ss = a.listenUsingRfcommWithServiceRecord(NAME, SERVICE)
                listen = ss
                while (running) {
                    val s = ss.accept() ?: break
                    runCatching { sock?.close() }
                    Thread({ talk(s) }, "handywatch-read").apply { isDaemon = true; start() }
                }
            } catch (e: SecurityException) {
                onState(false, "Allow Nearby devices for HandyWatch"); pause(10000)
            } catch (e: Exception) {
                if (running) pause(3000) // Bluetooth was switched off, or the socket closed
            } finally { runCatching { listen?.close() } }
        }
    }

    /** The watch: connect to the phone, and keep trying while it's away (3 s, then up to 60 s apart). */
    private fun dial() {
        var wait = 3000L
        while (running) {
            val addr = target()
            if (addr.isBlank()) { onState(false, "Choose your phone in Settings"); pause(10000); continue }
            val a = adapter()
            if (a == null) { pause(5000); continue }
            try {
                onState(false, "Connecting…")
                val dev = a.getRemoteDevice(addr)
                val s = dev.createRfcommSocketToServiceRecord(SERVICE)
                runCatching { a.cancelDiscovery() }
                s.connect()
                wait = 3000L
                talk(s)
            } catch (e: SecurityException) {
                onState(false, "Allow Nearby devices for HandyWatch")
            } catch (e: Exception) {
                onState(false, "Phone not found. Is HandyWatch running on it?")
            }
            if (!running) break
            pause(wait)
            wait = (wait * 2).coerceAtMost(60000L)
        }
    }

    /** Reads lines until the connection ends. */
    private fun talk(s: BluetoothSocket) {
        sock = s
        try {
            out = s.outputStream
            peer = runCatching { s.remoteDevice.name ?: s.remoteDevice.address }.getOrDefault("")
            connected = true
            onState(true, peer)
            val r = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8), 16384)
            while (running) {
                val line = r.readLine() ?: break
                if (line.isNotBlank()) runCatching { onLine(line) }
            }
        } catch (_: Exception) {
        } finally {
            if (sock === s) { connected = false; out = null; sock = null; onState(false, if (server) "Waiting for the watch" else "Connection lost") }
            runCatching { s.close() }
        }
    }
}
