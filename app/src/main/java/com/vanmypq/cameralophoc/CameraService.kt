package com.vanmypq.cameralophoc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Point
import android.media.MediaActionSound
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraService : LifecycleService() {

    companion object {
        const val ACTION_SHOT = "com.vanmypq.cameralophoc.SHOT"
        const val ACTION_SWITCH = "com.vanmypq.cameralophoc.SWITCH"
        const val ACTION_REBIND = "com.vanmypq.cameralophoc.REBIND"
        const val ACTION_STOP = "com.vanmypq.cameralophoc.STOP"
        private const val CH = "camera_lop_hoc"
        private const val NOTI_ID = 1
        private const val TAP_GAP_MS = 400L
        private const val COOLDOWN_MS = 1200L

        @Volatile
        var running = false

        @Volatile
        var instance: CameraService? = null
    }

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var overlay: View? = null
    private var orient: OrientationEventListener? = null
    private var sensorRotation = -1 // -1 = chưa biết (máy nằm phẳng / chưa có dữ liệu)
    private var taps = 0
    private var lastTap = 0L
    private var lastShot = 0L
    private var count = 0
    private var sound: MediaActionSound? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH, "Camera lớp học", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTI_ID, buildNotification("Chạm ${Cfg.taps(this)} lần liên tiếp vào màn hình để chụp"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        )
        sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
        setupOverlay()
        setupOrientation()
        setupCamera()
        running = true
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHOT -> shoot()
            ACTION_SWITCH -> { Cfg.setFront(this, !Cfg.front(this)); bind() }
            ACTION_REBIND -> bind()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        try { overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        overlay = null
        orient?.disable()
        try { provider?.unbindAll() } catch (_: Exception) {}
        io.shutdown()
        sound?.release()
        super.onDestroy()
    }

    // ---------- Phát hiện chạm liên tiếp (không cần nút nổi) ----------
    private fun setupOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val v = View(this)
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) onTap()
            false
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp)
            overlay = v
        } catch (_: Exception) {}
    }

    private fun onTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTap > TAP_GAP_MS) taps = 0
        lastTap = now
        taps++
        if (taps >= Cfg.taps(this)) {
            taps = 0
            shoot()
        }
    }

    // ---------- Hướng màn hình / cảm biến ----------
    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

    /** Hướng dùng để chụp: theo màn hình (mặc định) hoặc theo cảm biến nếu người dùng chọn và đã có dữ liệu. */
    private fun captureRotation(): Int =
        if (Cfg.sensorRot(this) && sensorRotation >= 0) sensorRotation else displayRotation()

    private fun setupOrientation() {
        orient = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                sensorRotation = when {
                    o >= 315 || o < 45 -> Surface.ROTATION_0
                    o < 135 -> Surface.ROTATION_270
                    o < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    // ---------- Camera ----------
    private fun setupCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            provider = f.get()
            bind()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        val wantFront = Cfg.front(this)
        // Chế độ đầy màn hình: lấy khung 16:9 rộng nhất để cắt ít nhất; ngược lại dùng 4:3 đầy đủ.
        val ratio = if (Cfg.fullScreen(this)) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        fun tryBind(front: Boolean): Boolean {
            return try {
                val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetAspectRatio(ratio)
                    .setTargetRotation(captureRotation())
                    .build()
                p.unbindAll()
                p.bindToLifecycle(this, sel, ic)
                imageCapture = ic
                true
            } catch (e: Exception) {
                false
            }
        }
        if (!tryBind(wantFront)) {
            if (tryBind(!wantFront)) {
                Cfg.setFront(this, !wantFront)
                notify("Máy không mở được camera ${if (wantFront) "trước" else "sau"}, đã chuyển sang camera còn lại")
            } else {
                imageCapture = null
                notify("Không mở được camera. Có thể app khác đang dùng camera.")
            }
        } else {
            notify("Camera ${if (wantFront) "trước" else "sau"} sẵn sàng. Chạm ${Cfg.taps(this)} lần để chụp")
        }
    }

    fun shoot() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShot < COOLDOWN_MS) return
        val ic = imageCapture ?: return
        if (!busy.compareAndSet(false, true)) return
        lastShot = now

        // Chốt hướng ngay lúc chụp để ảnh luôn đúng chiều với màn hình (dọc/ngang)
        val rot = captureRotation()
        ic.targetRotation = rot

        ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    saveProcessed(image, rot)
                    main.post {
                        count++
                        feedback()
                        notify("Đã chụp $count ảnh (Thư viện > CameraLopHoc)")
                    }
                } catch (t: Throwable) {
                    main.post { notify("Lưu ảnh lỗi: ${t.message}") }
                } finally {
                    image.close()
                    busy.set(false)
                }
            }

            override fun onError(e: ImageCaptureException) {
                busy.set(false)
                main.post { notify("Chụp lỗi: ${e.message}") }
            }
        })
    }

    /**
     * Xoay thật các điểm ảnh cho đúng chiều (không phụ thuộc EXIF), rồi cắt đúng tỉ lệ màn hình
     * (nếu bật "Ảnh đầy màn hình") và lưu vào Thư viện.
     */
    @Suppress("DEPRECATION")
    private fun saveProcessed(image: ImageProxy, rot: Int) {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        val deg = image.imageInfo.rotationDegrees

        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Không giải mã được ảnh")

        if (deg != 0) {
            val m = Matrix().apply { postRotate(deg.toFloat()) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }

        if (Cfg.fullScreen(this)) {
            val size = Point()
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(size)
            val longSide = maxOf(size.x, size.y).toFloat()
            val shortSide = minOf(size.x, size.y).toFloat()
            val landscape = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
            val ratioWH = if (landscape) longSide / shortSide else shortSide / longSide

            val bw = bmp.width
            val bh = bmp.height
            var cw = bw
            var ch = bh
            if (bw.toFloat() / bh > ratioWH) cw = (bh * ratioWH).toInt() else ch = (bw / ratioWH).toInt()
            if (cw != bw || ch != bh) {
                val c = Bitmap.createBitmap(bmp, (bw - cw) / 2, (bh - ch) / 2, cw, ch)
                bmp.recycle()
                bmp = c
            }
        }

        val name = "Lop_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraLopHoc")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw IOException("Không tạo được file ảnh")
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)) throw IOException("Ghi ảnh thất bại")
            } ?: throw IOException("Không mở được file ảnh")
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, cv, null, null)
        } catch (t: Throwable) {
            contentResolver.delete(uri, null, null)
            throw t
        } finally {
            bmp.recycle()
        }
    }

    private fun feedback() {
        if (Cfg.sound(this)) sound?.play(MediaActionSound.SHUTTER_CLICK)
        if (Cfg.vibrate(this)) {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ---------- Thông báo ----------
    private fun pi(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, CameraService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Camera lớp học đang chạy")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Chụp", pi(ACTION_SHOT, 1))
            .addAction(0, "Đổi camera", pi(ACTION_SWITCH, 2))
            .addAction(0, "Dừng", pi(ACTION_STOP, 3))
            .build()
    }

    private fun notify(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTI_ID, buildNotification(text))
    }
}
