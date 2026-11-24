package com.vanmypq.cameralophoc

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * Dịch vụ Trợ năng chỉ để nhận phím âm lượng làm phím tắt chụp.
 * Lưu ý: Android không cho app nào đọc phím Nguồn, nên không dùng được "Tăng âm lượng + Nguồn".
 */
class KeyService : AccessibilityService() {

    private var tUp = 0L
    private var tDown = 0L
    private var lastUp = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false

        val mode = Cfg.keyMode(this)
        val cam = CameraService.instance
        // Chỉ hoạt động khi đã bật camera nền và đã chọn kiểu phím tắt
        if (mode == 0 || cam == null) return false

        var fire = false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = SystemClock.uptimeMillis()
            when (mode) {
                1 -> { // Nhấn cùng lúc Tăng + Giảm âm lượng
                    if (code == KeyEvent.KEYCODE_VOLUME_UP) tUp = now else tDown = now
                    if (tUp > 0 && tDown > 0 && abs(tUp - tDown) <= 400) {
                        fire = true
                        tUp = 0; tDown = 0
                    }
                }
                2 -> { // Nhấn đúp Tăng âm lượng
                    if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                        if (lastUp > 0 && now - lastUp <= 500) {
                            fire = true
                            lastUp = 0
                        } else {
                            lastUp = now
                        }
                    }
                }
            }
            if (fire) cam.shoot()
        }
        // Nuốt phím nếu vừa kích hoạt chụp, hoặc nếu người dùng chọn chặn đổi âm lượng
        return fire || Cfg.keyBlock(this)
    }
}
