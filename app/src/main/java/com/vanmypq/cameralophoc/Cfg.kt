package com.vanmypq.cameralophoc

import android.content.Context

object Cfg {
    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun front(c: Context) = p(c).getBoolean("front", false)
    fun setFront(c: Context, v: Boolean) = p(c).edit().putBoolean("front", v).apply()

    /** Số lần chạm liên tiếp để chụp: mặc định 3 (có thể chọn 2) */
    fun taps(c: Context) = p(c).getInt("taps", 3)
    fun setTaps(c: Context, v: Int) = p(c).edit().putInt("taps", v).apply()

    fun sound(c: Context) = p(c).getBoolean("sound", true)
    fun setSound(c: Context, v: Boolean) = p(c).edit().putBoolean("sound", v).apply()

    fun vibrate(c: Context) = p(c).getBoolean("vibrate", true)
    fun setVibrate(c: Context, v: Boolean) = p(c).edit().putBoolean("vibrate", v).apply()

    /** Ảnh đầy màn hình: cắt đúng tỉ lệ màn hình điện thoại (dọc/ngang). Tắt = giữ nguyên khung camera. */
    fun fullScreen(c: Context) = p(c).getBoolean("full", true)
    fun setFullScreen(c: Context, v: Boolean) = p(c).edit().putBoolean("full", v).apply()

    /** Xoay ảnh theo cảm biến (dùng khi đã khoá xoay màn hình). Mặc định theo hướng màn hình. */
    fun sensorRot(c: Context) = p(c).getBoolean("sensorRot", false)
    fun setSensorRot(c: Context, v: Boolean) = p(c).edit().putBoolean("sensorRot", v).apply()

    /** Phím tắt âm lượng: 0 = tắt, 1 = nhấn cùng lúc Tăng + Giảm âm lượng, 2 = nhấn đúp Tăng âm lượng */
    fun keyMode(c: Context) = p(c).getInt("keyMode", 1)
    fun setKeyMode(c: Context, v: Int) = p(c).edit().putInt("keyMode", v).apply()

    /** Chặn thay đổi âm lượng khi bấm phím âm lượng (lúc camera nền đang chạy) */
    fun keyBlock(c: Context) = p(c).getBoolean("keyBlock", false)
    fun setKeyBlock(c: Context, v: Boolean) = p(c).edit().putBoolean("keyBlock", v).apply()
}
