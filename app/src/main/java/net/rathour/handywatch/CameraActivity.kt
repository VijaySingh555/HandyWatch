@file:Suppress("DEPRECATION") // android.hardware.Camera: simple, and works on every Android version

package net.rathour.handywatch

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.media.AudioManager
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The phone's camera screen, run from the watch's Control > Camera page: photos and videos
 * (now, or after a 5 or 10 second countdown with beeps), a live preview sent to the watch, and
 * a black screen so the phone can film with its screen dark. Photos go to Pictures/HandyWatch,
 * videos to Movies/HandyWatch.
 */
class CameraActivity : Activity(), TextureView.SurfaceTextureListener {
    companion object { @Volatile var current: CameraActivity? = null }

    private lateinit var texture: TextureView
    private lateinit var countText: TextView
    private lateinit var recText: TextView
    private lateinit var blackout: View
    private val main = Handler(Looper.getMainLooper())
    private val jpegWork = Executors.newSingleThreadExecutor()
    private var cam: Camera? = null
    private var camId = 0
    private var front = false
    private var busy = false
    private var counting = 0
    private var countVideo = false
    private var pendingShoot = -1
    private var pendingVideo = false
    private var blank = false
    private var savedBrightness = -1f
    private var previewW = 0
    private var previewH = 0
    @Volatile private var encoding = false
    // video
    private var recorder: MediaRecorder? = null
    private var recUri: Uri? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recFile: File? = null
    private var recStart = 0L
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        front = Prefs.p(this).getBoolean("front", false)
        val d = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        texture = TextureView(this).also { it.surfaceTextureListener = this }
        root.addView(texture, FrameLayout.LayoutParams(-1, -1))
        countText = TextView(this).apply {
            textSize = 120f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 0f, 0xFF000000.toInt()); visibility = View.GONE
        }
        root.addView(countText, FrameLayout.LayoutParams(-1, -1))
        recText = TextView(this).apply {
            textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply { setColor(0xFFE53935.toInt()); cornerRadius = 14 * d }; visibility = View.GONE
        }
        root.addView(recText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (30 * d).toInt() })
        val shutter = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()); setStroke((5 * d).toInt(), 0xFF8A8A86.toInt()) }
            contentDescription = "Shutter"
            setOnClickListener { shoot(0, false) }
        }
        root.addView(shutter, FrameLayout.LayoutParams((76 * d).toInt(), (76 * d).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (36 * d).toInt() })
        fun roundBtn(t: String, g: Int, desc: String, on: () -> Unit) = TextView(this).apply {
            text = t; textSize = 22f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; contentDescription = desc
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xB3242424.toInt()) }
            setOnClickListener { on() }
            root.addView(this, FrameLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt(), g).apply {
                bottomMargin = (48 * d).toInt(); topMargin = (28 * d).toInt(); leftMargin = (28 * d).toInt(); rightMargin = (28 * d).toInt()
            })
        }
        roundBtn("⟲", Gravity.BOTTOM or Gravity.END, "Switch camera") { switchLens() }
        roundBtn("✕", Gravity.TOP or Gravity.START, "Close") { finish() }
        // the black screen: covers everything at the lowest brightness; the camera keeps running. Tap to come back.
        blackout = View(this).apply { setBackgroundColor(0xFF000000.toInt()); visibility = View.GONE; setOnClickListener { setBlank(false) } }
        root.addView(blackout, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        handleShootExtra(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShootExtra(intent)
    }

    private fun handleShootExtra(i: android.content.Intent?) {
        if (i?.getBooleanExtra("shoot", false) == true) {
            pendingShoot = i.getIntExtra("delay", 0)
            pendingVideo = i.getBooleanExtra("video", false)
            i.removeExtra("shoot")
            if (cam != null) firePending(600)
        }
    }

    private fun firePending(after: Long) {
        if (pendingShoot < 0) return
        val d = pendingShoot; val v = pendingVideo
        pendingShoot = -1
        main.postDelayed({ shoot(d, v) }, after)
    }

    override fun onResume() {
        super.onResume()
        if (texture.isAvailable) openCamera(texture.surfaceTexture!!)
        sendState()
    }

    override fun onPause() {
        stopRecording()
        closeCamera()
        counting = 0
        main.removeCallbacksAndMessages(null)
        countText.visibility = View.GONE
        super.onPause()
    }

    override fun onDestroy() {
        if (current === this) current = null
        PhoneService.instance?.cameraState(JSONObject().put("open", false))
        runCatching { tone?.release() }
        jpegWork.shutdownNow()
        super.onDestroy()
    }

    /** Everything the watch's camera page shows. */
    fun sendState() {
        val s = PhoneService.instance ?: return
        s.cameraState(JSONObject().put("open", true).put("front", front).put("count", counting).put("countVideo", countVideo)
            .put("rec", recorder != null).put("secs", if (recorder != null) (SystemClock.elapsedRealtime() - recStart) / 1000 else 0).put("blank", blank))
    }

    // ---- TextureView
    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera(st) }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fit() }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { closeCamera(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun granted(p: String) = Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun openCamera(st: SurfaceTexture) {
        if (cam != null) return
        if (!granted(Manifest.permission.CAMERA)) {
            PhoneService.instance?.toWatch("Allow the camera in HandyWatch on the phone")
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 5)
            return
        }
        try {
            camId = pickCamera(front)
            val c = Camera.open(camId)
            val p = c.parameters
            val pic = p.supportedPictureSizes.maxByOrNull { it.width.toLong() * it.height }
            if (pic != null) p.setPictureSize(pic.width, pic.height)
            val aspect = if (pic != null) pic.width.toFloat() / pic.height else 4f / 3f
            val prev = p.supportedPreviewSizes.filter { it.width <= 1920 && Math.abs(it.width.toFloat() / it.height - aspect) < 0.05f }
                .maxByOrNull { it.width * it.height } ?: p.supportedPreviewSizes.maxByOrNull { it.width * it.height }
            if (prev != null) p.setPreviewSize(prev.width, prev.height)
            if (p.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true)
                p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            p.setRotation(jpegRotation())
            c.parameters = p
            c.setDisplayOrientation(displayRotation())
            c.setPreviewTexture(st)
            val ps = c.parameters.previewSize
            previewW = ps.width; previewH = ps.height
            startFrames(c)
            c.startPreview()
            cam = c
            fit()
            firePending(900)
        } catch (e: Exception) {
            closeCamera()
            PhoneService.instance?.toWatch("The phone's camera couldn't start: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (texture.isAvailable) openCamera(texture.surfaceTexture!!)
    }

    private fun closeCamera() {
        runCatching { cam?.setPreviewCallbackWithBuffer(null) }
        runCatching { cam?.stopPreview() }
        runCatching { cam?.release() }
        cam = null
        busy = false
    }

    // ---- live preview for the watch: about 320 pixels across, as often as the link allows (up to ~10 a second)
    private fun startFrames(c: Camera) {
        val size = previewW * previewH * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
        repeat(2) { c.addCallbackBuffer(ByteArray(size)) }
        c.setPreviewCallbackWithBuffer { data, camera ->
            if (data != null && grabWanted) grabFrame(data, previewW, previewH)
            val s = PhoneService.instance
            if (data != null && s != null && !encoding && s.wantsFrame()) {
                encoding = true
                val w = previewW; val h = previewH; val rot = displayRotation()
                val small = shrinkNv21(data, w, h)
                camera.addCallbackBuffer(data)
                jpegWork.execute {
                    try {
                        val out = ByteArrayOutputStream()
                        YuvImage(small.first, ImageFormat.NV21, small.second, small.third, null).compressToJpeg(Rect(0, 0, small.second, small.third), 55, out)
                        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                        main.post { PhoneService.instance?.sendFrame(b64, if (front) (360 - rot) % 360 else rot) }
                    } catch (_: Exception) { } finally { encoding = false }
                }
            } else if (data != null) camera.addCallbackBuffer(data)
        }
    }

    /** Picks every k-th pixel of an NV21 frame, so the larger side is about 320 pixels. */
    private fun shrinkNv21(src: ByteArray, w: Int, h: Int): Triple<ByteArray, Int, Int> {
        val k = maxOf(1, maxOf(w, h) / 320)
        val nw = (w / k) and 1.inv()
        val nh = (h / k) and 1.inv()
        val out = ByteArray(nw * nh * 3 / 2)
        var o = 0
        for (r in 0 until nh) { val row = r * k * w; for (c in 0 until nw) out[o++] = src[row + c * k] }
        val uv = w * h
        for (r in 0 until nh / 2) {
            val row = uv + (r * k) * w
            for (c in 0 until nw / 2) { val i = row + (c * k) * 2; out[o++] = src[i]; out[o++] = src[i + 1] }
        }
        return Triple(out, nw, nh)
    }

    private fun pickCamera(wantFront: Boolean): Int {
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if ((info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) == wantFront) return i
        }
        return 0
    }

    private fun screenDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
    }

    private fun displayRotation(): Int {
        val info = Camera.CameraInfo(); Camera.getCameraInfo(camId, info)
        val deg = screenDegrees()
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (360 - (info.orientation + deg) % 360) % 360
        else (info.orientation - deg + 360) % 360
    }

    private fun jpegRotation(): Int {
        val info = Camera.CameraInfo(); Camera.getCameraInfo(camId, info)
        val deg = screenDegrees()
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (info.orientation + deg) % 360
        else (info.orientation - deg + 360) % 360
    }

    /** Fill the screen with the preview, without stretching it. */
    private fun fit() {
        if (cam == null || previewW == 0) return
        val vw = texture.width.toFloat(); val vh = texture.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val rot = displayRotation()
        val pw = if (rot % 180 == 0) previewW.toFloat() else previewH.toFloat()
        val ph = if (rot % 180 == 0) previewH.toFloat() else previewW.toFloat()
        val scale = maxOf(vw / pw, vh / ph)
        val m = Matrix()
        m.setRectToRect(RectF(0f, 0f, vw, vh), RectF(0f, 0f, pw * scale, ph * scale), Matrix.ScaleToFit.FILL)
        m.postTranslate((vw - pw * scale) / 2f, (vh - ph * scale) / 2f)
        texture.setTransform(m)
    }

    fun switchLens() {
        if (recorder != null) { PhoneService.instance?.toWatch("Stop recording first"); return }
        front = !front
        Prefs.set(this, "front", front)
        closeCamera()
        if (texture.isAvailable) openCamera(texture.surfaceTexture!!)
        sendState()
    }

    // ---- the black screen
    fun setBlank(on: Boolean) {
        blank = on
        blackout.visibility = if (on) View.VISIBLE else View.GONE
        val lp = window.attributes
        if (on) { savedBrightness = lp.screenBrightness; lp.screenBrightness = 0.0f }
        else lp.screenBrightness = if (savedBrightness >= 0f) savedBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        blackout.systemUiVisibility = if (on) View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else 0
        sendState()
    }

    // ---- photo or video, now or after a countdown. Pressing again during a countdown cancels it; in video, pressing while recording stops.
    fun shoot(delay: Int, video: Boolean) {
        if (counting > 0) { counting = 0; main.removeCallbacks(tickRun); countText.visibility = View.GONE; sendState(); return }
        if (recorder != null) { stopRecording(); return }
        countVideo = video
        if (delay <= 0) { fire(); return }
        counting = delay
        tick()
    }

    private val tickRun = Runnable {
        counting--
        if (counting <= 0) { countText.visibility = View.GONE; fire() } else tick()
    }

    private fun tick() {
        if (counting <= 0) return
        countText.text = counting.toString(); countText.visibility = if (blank) View.GONE else View.VISIBLE
        runCatching { tone?.startTone(if (counting <= 2) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 120) }
        sendState()
        main.postDelayed(tickRun, 1000)
    }

    private fun fire() { if (countVideo) startRecording() else capture(); sendState() }

    private val captureTimeout = Runnable {
        // the camera never returned the photo: take it from the preview instead
        if (busy) { busy = false; runCatching { cam?.startPreview() }; grabPreview("the camera didn't finish the photo") }
    }

    private fun capture() {
        val c = cam
        if (c == null) { PhoneService.instance?.toWatch("The phone's camera isn't open"); return }
        if (busy) return
        busy = true
        try {
            runCatching { c.parameters = c.parameters.apply { setRotation(jpegRotation()) } }
            runCatching { c.enableShutterSound(true) }
            main.postDelayed(captureTimeout, 5000)
            // the shutter moment: the watch buzzes and flashes
            c.takePicture({ PhoneService.instance?.send("snap", "kind" to "photo") }, null) { data, camera ->
                main.removeCallbacks(captureTimeout)
                busy = false
                runCatching { camera.startPreview() }
                runCatching { startFrames(camera) }
                if (data == null || data.isEmpty()) grabPreview("the camera returned an empty photo")
                else Thread { save(data) }.start()
            }
        } catch (e: Exception) {
            main.removeCallbacks(captureTimeout)
            busy = false
            runCatching { c.startPreview(); startFrames(c) }
            grabPreview(e.message ?: e.javaClass.simpleName)
        }
    }

    // ---- fallback: a full-size still from the live preview (lower quality than a real photo, but never lost)
    @Volatile private var grabWanted = false
    private var grabReason = ""

    private fun grabPreview(reason: String) {
        grabReason = reason
        grabWanted = true
        main.postDelayed({ if (grabWanted) { grabWanted = false; PhoneService.instance?.toWatch("The photo failed: $reason") } }, 3000)
    }

    /** Called from the preview callback with a whole frame when a fallback still is wanted. */
    private fun grabFrame(nv21: ByteArray, w: Int, h: Int) {
        grabWanted = false
        val copy = nv21.copyOf()
        val rot = jpegRotation()
        val front = this.front
        Thread {
            try {
                val out = ByteArrayOutputStream()
                YuvImage(copy, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 92, out)
                var jpg = out.toByteArray()
                if (rot != 0 || front) {
                    val b = BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
                    val m = Matrix().apply { postRotate(rot.toFloat()) }
                    val r = android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
                    val o2 = ByteArrayOutputStream(); r.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, o2); jpg = o2.toByteArray()
                }
                main.post { PhoneService.instance?.send("snap", "kind" to "photo"); PhoneService.instance?.toWatch("Saved from the preview ($grabReason)") }
                save(jpg)
            } catch (e: Exception) { main.post { PhoneService.instance?.toWatch("The photo failed: $grabReason") } }
        }.start()
    }

    @Volatile private var saveError = ""

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())

    private fun save(jpg: ByteArray) {
        val name = "HandyWatch_" + stamp() + ".jpg"
        var ok = false
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HandyWatch")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(jpg); ok = true }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "HandyWatch")
                dir.mkdirs()
                val f = File(dir, name)
                f.writeBytes(jpg); ok = true
                MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
            }
        } catch (e: Exception) { saveError = e.message ?: e.javaClass.simpleName }
        val thumb = runCatching { thumbnail(jpg) }.getOrNull()
        main.post {
            val s = PhoneService.instance ?: return@post
            if (!ok) s.toWatch("The photo couldn't be saved on the phone: $saveError")
            else if (thumb != null) s.sendShot(thumb) else s.toWatch("Photo saved")
        }
    }

    // ---- video (Movies/HandyWatch); sound too if the microphone is allowed
    private fun startRecording() {
        val c = cam ?: return
        if (recorder != null) return
        val sound = granted(Manifest.permission.RECORD_AUDIO)
        if (!sound && Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 6)
        val name = "HandyWatch_" + stamp() + ".mp4"
        val mr = MediaRecorder()
        try {
            val prof = when {
                CamcorderProfile.hasProfile(camId, CamcorderProfile.QUALITY_1080P) -> CamcorderProfile.get(camId, CamcorderProfile.QUALITY_1080P)
                else -> CamcorderProfile.get(camId, CamcorderProfile.QUALITY_HIGH)
            }
            c.unlock()
            mr.setCamera(c)
            if (sound) mr.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            mr.setVideoSource(MediaRecorder.VideoSource.CAMERA)
            if (sound) mr.setProfile(prof) else {
                mr.setOutputFormat(prof.fileFormat)
                mr.setVideoFrameRate(prof.videoFrameRate)
                mr.setVideoSize(prof.videoFrameWidth, prof.videoFrameHeight)
                mr.setVideoEncodingBitRate(prof.videoBitRate)
                mr.setVideoEncoder(prof.videoCodec)
            }
            mr.setOrientationHint(jpegRotation())
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HandyWatch")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException()
                recUri = uri
                recPfd = contentResolver.openFileDescriptor(uri, "w")
                mr.setOutputFile(recPfd!!.fileDescriptor)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "HandyWatch")
                dir.mkdirs()
                recFile = File(dir, name)
                mr.setOutputFile(recFile!!.absolutePath)
            }
            mr.prepare()
            mr.start()
            recorder = mr
            recStart = SystemClock.elapsedRealtime()
            PhoneService.instance?.send("snap", "kind" to "recstart")
            main.post(recTick)
        } catch (e: Exception) {
            runCatching { mr.release() }
            runCatching { c.lock() }
            cleanupFailed()
            PhoneService.instance?.toWatch("The video couldn't start")
        }
        sendState()
    }

    private val recTick = object : Runnable {
        override fun run() {
            if (recorder == null) { recText.visibility = View.GONE; return }
            val s = (SystemClock.elapsedRealtime() - recStart) / 1000
            recText.text = "● %d:%02d".format(s / 60, s % 60)
            recText.visibility = if (blank) View.GONE else View.VISIBLE
            sendState()
            main.postDelayed(this, 1000)
        }
    }

    private fun stopRecording() {
        val mr = recorder ?: return
        recorder = null
        PhoneService.instance?.send("snap", "kind" to "recstop")
        var ok = true
        try { mr.stop() } catch (_: Exception) { ok = false } // too short: nothing was recorded
        runCatching { mr.release() }
        runCatching { cam?.lock() }
        runCatching { cam?.let { it.startPreview(); startFrames(it) } }
        runCatching { recPfd?.close() }
        recPfd = null
        val uri = recUri
        if (uri != null) {
            if (ok) runCatching { contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) }
            else runCatching { contentResolver.delete(uri, null, null) }
        }
        recFile?.let { f -> if (ok) MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("video/mp4"), null) else f.delete() }
        recUri = null; recFile = null
        recText.visibility = View.GONE
        PhoneService.instance?.toWatch(if (ok) "Video saved" else "The video was too short to keep")
        sendState()
    }

    private fun cleanupFailed() {
        runCatching { recPfd?.close() }; recPfd = null
        recUri?.let { u -> runCatching { contentResolver.delete(u, null, null) } }; recUri = null
        recFile?.delete(); recFile = null
    }

    /** Most cameras mark the photo's rotation instead of turning its pixels. */
    private fun exifDegrees(jpg: ByteArray): Int {
        if (Build.VERSION.SDK_INT < 24) return 0
        return runCatching {
            when (android.media.ExifInterface(java.io.ByteArrayInputStream(jpg)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
    }

    /** A small copy for the watch (about 10 KB). */
    private fun thumbnail(jpg: ByteArray): String? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpg, 0, jpg.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 320) sample *= 2
        val b = BitmapFactory.decodeByteArray(jpg, 0, jpg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = 320f / maxOf(b.width, b.height)
        val m = Matrix().apply { postScale(scale, scale); postRotate(exifDegrees(jpg).toFloat()) }
        val small = android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        val out = ByteArrayOutputStream()
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
