@file:Suppress("DEPRECATION") // android.hardware.Camera: simple, and works on every Android version

package net.rathour.handywatch

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The phone's camera screen, opened from the watch's Control > Camera page. The watch's
 * shutter takes a photo now or after a 5 or 10 second countdown (shown big on the phone, with
 * a beep each second); a small copy of each photo goes back to the watch. Photos are saved in
 * Pictures/HandyWatch.
 */
class CameraActivity : Activity(), TextureView.SurfaceTextureListener {
    companion object { @Volatile var current: CameraActivity? = null }

    private lateinit var texture: TextureView
    private lateinit var countText: TextView
    private val main = Handler(Looper.getMainLooper())
    private var cam: Camera? = null
    private var camId = 0
    private var front = false
    private var busy = false
    private var counting = 0
    private var pendingShoot = -1
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
        val shutter = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()); setStroke((5 * d).toInt(), 0xFF8A8A86.toInt()) }
            contentDescription = "Shutter"
            setOnClickListener { shoot(0) }
        }
        root.addView(shutter, FrameLayout.LayoutParams((76 * d).toInt(), (76 * d).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (36 * d).toInt() })
        fun roundBtn(t: String, g: Int, desc: String, on: () -> Unit) = TextView(this).apply {
            text = t; textSize = 22f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; contentDescription = desc
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xB32B2B29.toInt()) }
            setOnClickListener { on() }
            root.addView(this, FrameLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt(), g).apply {
                bottomMargin = (48 * d).toInt(); topMargin = (28 * d).toInt(); leftMargin = (28 * d).toInt(); rightMargin = (28 * d).toInt()
            })
        }
        roundBtn("⟲", Gravity.BOTTOM or Gravity.END, "Switch camera") { switchLens() }
        roundBtn("✕", Gravity.TOP or Gravity.START, "Close") { finish() }
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
            i.removeExtra("shoot")
            if (cam != null) { val d = pendingShoot; pendingShoot = -1; main.postDelayed({ shoot(d) }, 600) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (texture.isAvailable) openCamera(texture.surfaceTexture!!)
        PhoneService.instance?.cameraState(true, front, 0)
    }

    override fun onPause() {
        closeCamera()
        counting = 0
        main.removeCallbacksAndMessages(null)
        countText.visibility = View.GONE
        super.onPause()
    }

    override fun onDestroy() {
        if (current === this) current = null
        PhoneService.instance?.cameraState(false, front, 0)
        runCatching { tone?.release() }
        super.onDestroy()
    }

    // ---- TextureView
    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera(st) }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fit() }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { closeCamera(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun openCamera(st: SurfaceTexture) {
        if (cam != null) return
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            PhoneService.instance?.toWatch("Allow the camera in HandyWatch on the phone")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 5)
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
            c.startPreview()
            cam = c
            fit()
            if (pendingShoot >= 0) { val d = pendingShoot; pendingShoot = -1; main.postDelayed({ shoot(d) }, 900) }
        } catch (e: Exception) {
            closeCamera()
            PhoneService.instance?.toWatch("The phone's camera couldn't start")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (texture.isAvailable) openCamera(texture.surfaceTexture!!)
    }

    private fun closeCamera() {
        runCatching { cam?.stopPreview() }
        runCatching { cam?.release() }
        cam = null
        busy = false
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
        val c = cam ?: return
        val s = runCatching { c.parameters.previewSize }.getOrNull() ?: return
        val vw = texture.width.toFloat(); val vh = texture.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val rot = displayRotation()
        val pw = if (rot % 180 == 0) s.width.toFloat() else s.height.toFloat()
        val ph = if (rot % 180 == 0) s.height.toFloat() else s.width.toFloat()
        val scale = maxOf(vw / pw, vh / ph)
        val m = Matrix()
        m.setRectToRect(RectF(0f, 0f, vw, vh), RectF(0f, 0f, pw * scale, ph * scale), Matrix.ScaleToFit.FILL)
        m.postTranslate((vw - pw * scale) / 2f, (vh - ph * scale) / 2f)
        texture.setTransform(m)
    }

    fun switchLens() {
        front = !front
        Prefs.set(this, "front", front)
        closeCamera()
        if (texture.isAvailable) openCamera(texture.surfaceTexture!!)
        PhoneService.instance?.cameraState(true, front, 0)
    }

    /** Take a photo now, or after a countdown in seconds. Pressing again during a countdown cancels it. */
    fun shoot(delay: Int) {
        if (counting > 0) { counting = 0; main.removeCallbacksAndMessages(null); countText.visibility = View.GONE; PhoneService.instance?.cameraState(true, front, 0); return }
        if (delay <= 0) { capture(); return }
        counting = delay
        tick()
    }

    private fun tick() {
        if (counting <= 0) return
        countText.text = counting.toString(); countText.visibility = View.VISIBLE
        runCatching { tone?.startTone(if (counting <= 2) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 120) }
        PhoneService.instance?.cameraState(true, front, counting)
        main.postDelayed({
            counting--
            if (counting <= 0) { countText.visibility = View.GONE; capture(); PhoneService.instance?.cameraState(true, front, 0) } else tick()
        }, 1000)
    }

    private fun capture() {
        val c = cam ?: return
        if (busy) return
        busy = true
        try {
            c.parameters = c.parameters.apply { setRotation(jpegRotation()) }
            c.takePicture({ }, null) { data, camera ->
                busy = false
                runCatching { camera.startPreview() }
                Thread { save(data) }.start()
            }
        } catch (e: Exception) {
            busy = false
            PhoneService.instance?.toWatch("The photo couldn't be taken")
        }
    }

    private fun save(jpg: ByteArray) {
        val name = "HandyWatch_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date()) + ".jpg"
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
        } catch (_: Exception) { }
        val thumb = runCatching { thumbnail(jpg) }.getOrNull()
        main.post {
            val s = PhoneService.instance ?: return@post
            if (!ok) s.toWatch("The photo couldn't be saved on the phone")
            else if (thumb != null) s.sendShot(thumb) else s.toWatch("Photo saved")
        }
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
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 240) sample *= 2
        val b = BitmapFactory.decodeByteArray(jpg, 0, jpg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = 240f / maxOf(b.width, b.height)
        val m = Matrix().apply { postScale(scale, scale); postRotate(exifDegrees(jpg).toFloat()) }
        val small = android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        val out = ByteArrayOutputStream()
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
