package com.vanmypq.cameralophoc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var rbBack: RadioButton
    private lateinit var rbFront: RadioButton
    private lateinit var rbTap2: RadioButton
    private lateinit var rbTap3: RadioButton

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun sendToService(action: String) {
        if (CameraService.running) {
            startService(Intent(this, CameraService::class.java).setAction(action))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        fun label(t: String, size: Float = 16f) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, dp(12), 0, dp(4))
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }
        }

        root.addView(label("Camera Lớp Học", 24f))
        status = label("", 14f)
        root.addView(status)

        root.addView(button("1. Cấp quyền camera (và thông báo)") {
            val perms = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), 1)
        })
        root.addView(button("2. Cho phép hiển thị trên ứng dụng khác") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("3. Tắt tối ưu pin cho app (khuyến nghị)") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        root.addView(button("4. Bật phím tắt âm lượng (Cài đặt Trợ năng)") {
            Toast.makeText(this, "Tìm \"Camera Lớp Học - phím tắt âm lượng\" rồi bật", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(label("Phím tắt chụp bằng phím âm lượng"))
        val rbKeyOff = RadioButton(this).apply { text = "Tắt"; id = View.generateViewId() }
        val rbKeyBoth = RadioButton(this).apply { text = "Nhấn cùng lúc Tăng + Giảm âm lượng"; id = View.generateViewId() }
        val rbKeyDouble = RadioButton(this).apply { text = "Nhấn đúp phím Tăng âm lượng"; id = View.generateViewId() }
        root.addView(RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(rbKeyOff); addView(rbKeyBoth); addView(rbKeyDouble)
            when (Cfg.keyMode(this@MainActivity)) {
                0 -> rbKeyOff.isChecked = true
                2 -> rbKeyDouble.isChecked = true
                else -> rbKeyBoth.isChecked = true
            }
            setOnCheckedChangeListener { _, id ->
                Cfg.setKeyMode(this@MainActivity, when (id) { rbKeyOff.id -> 0; rbKeyDouble.id -> 2; else -> 1 })
            }
        })
        root.addView(CheckBox(this).apply {
            text = "Chặn đổi âm lượng khi camera nền đang chạy (không hiện thanh âm lượng trên tivi)"
            isChecked = Cfg.keyBlock(this@MainActivity)
            setOnCheckedChangeListener { _, c -> Cfg.setKeyBlock(this@MainActivity, c) }
        })

        root.addView(label("Camera"))
        rbBack = RadioButton(this).apply { text = "Camera sau"; id = View.generateViewId() }
        rbFront = RadioButton(this).apply { text = "Camera trước"; id = View.generateViewId() }
        root.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(rbBack); addView(rbFront)
            setOnCheckedChangeListener { _, id ->
                Cfg.setFront(this@MainActivity, id == rbFront.id)
                sendToService(CameraService.ACTION_REBIND)
            }
        })

        root.addView(label("Số lần chạm liên tiếp để chụp"))
        rbTap2 = RadioButton(this).apply { text = "Chạm đúp (2 lần)"; id = View.generateViewId() }
        rbTap3 = RadioButton(this).apply { text = "Chạm 3 lần"; id = View.generateViewId() }
        root.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(rbTap2); addView(rbTap3)
            setOnCheckedChangeListener { _, id ->
                Cfg.setTaps(this@MainActivity, if (id == rbTap3.id) 3 else 2)
            }
        })

        root.addView(CheckBox(this).apply {
            text = "Ảnh đầy màn hình (cắt đúng tỉ lệ màn hình, dọc/ngang tự theo)"
            isChecked = Cfg.fullScreen(this@MainActivity)
            setOnCheckedChangeListener { _, c ->
                Cfg.setFullScreen(this@MainActivity, c)
                sendToService(CameraService.ACTION_REBIND)
            }
        })
        root.addView(CheckBox(this).apply {
            text = "Xoay ảnh theo cảm biến (chỉ bật khi đã khoá xoay màn hình)"
            isChecked = Cfg.sensorRot(this@MainActivity)
            setOnCheckedChangeListener { _, c -> Cfg.setSensorRot(this@MainActivity, c) }
        })
        root.addView(CheckBox(this).apply {
            text = "Phát tiếng \"tách\" khi chụp"
            isChecked = Cfg.sound(this@MainActivity)
            setOnCheckedChangeListener { _, c -> Cfg.setSound(this@MainActivity, c) }
        })
        root.addView(CheckBox(this).apply {
            text = "Rung nhẹ khi chụp"
            isChecked = Cfg.vibrate(this@MainActivity)
            setOnCheckedChangeListener { _, c -> Cfg.setVibrate(this@MainActivity, c) }
        })

        toggle = button("") {
            if (CameraService.running) {
                startService(Intent(this, CameraService::class.java).setAction(CameraService.ACTION_STOP))
            } else if (!hasCamera()) {
                Toast.makeText(this, "Cần cấp quyền camera trước", Toast.LENGTH_LONG).show()
            } else if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Cần bước 2: cho phép hiển thị trên ứng dụng khác", Toast.LENGTH_LONG).show()
            } else {
                ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
            }
            toggle.postDelayed({ refresh() }, 600)
        }
        root.addView(toggle)

        root.addView(button("Chụp thử ngay") { sendToService(CameraService.ACTION_SHOT) })

        root.addView(label(
            "Cách dùng: bấm BẬT, rồi thoát ra chiếu bài/video như bình thường. " +
            "Chạm 3 lần liên tiếp vào bất kỳ đâu trên màn hình để chụp (không có nút nổi). Hoặc dùng nút \"Chụp\" ngay trong thanh thông báo. " +
            "Ảnh lưu trong Thư viện > CameraLopHoc. " +
            "Lưu ý: chạm 3 lần nhanh ở bất kỳ đâu (kể cả khi thao tác app khác) cũng sẽ kích hoạt chụp.", 13f
        ))
    }

    override fun onResume() {
        super.onResume()
        rbBack.isChecked = !Cfg.front(this); rbFront.isChecked = Cfg.front(this)
        rbTap2.isChecked = Cfg.taps(this) != 3; rbTap3.isChecked = Cfg.taps(this) == 3
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        refresh()
    }

    private fun keyServiceOn(): Boolean {
        val v = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return v.contains("$packageName/") && v.contains("KeyService")
    }

    private fun refresh() {
        fun ok(b: Boolean) = if (b) "✓" else "✗"
        status.text = "${ok(hasCamera())} Quyền camera    ${ok(Settings.canDrawOverlays(this))} Hiển thị trên ứng dụng khác\n" +
            "${ok(keyServiceOn())} Phím tắt âm lượng (Trợ năng)\n" +
            "Trạng thái: " + if (CameraService.running) "ĐANG CHẠY" else "đang tắt"
        toggle.text = if (CameraService.running) "TẮT camera nền" else "BẬT camera nền"
    }
}
