package com.weekd.miracastreceiver.util

import android.content.Context

/**
 * 应用设置（用户可在主界面里改的开关）。
 *
 * 单独放在 SharedPreferences 里，和 AirPlay 配对信息、设备 UUID 那几个
 * 内部用的 prefs 文件分开，避免清配对时误删用户设置。
 */
object AppSettings {

    private const val PREFS_NAME = "miracast_settings"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_SEEK_USE_PERCENT = "seek_use_percent"
    private const val KEY_REWIND_VALUE = "rewind_value"
    private const val KEY_FORWARD_VALUE = "forward_value"
    private const val KEY_LONG_PRESS_SPEED = "long_press_speed"
    private const val KEY_CONTROLLER_TIMEOUT_MS = "controller_timeout_ms"
    private const val KEY_ACCENT_INDEX = "accent_index"
    private const val KEY_BACKGROUND_URI = "background_uri"
    private const val KEY_BACKGROUND_TYPE = "background_type"

    // 背景类型
    const val BACKGROUND_NONE = "none"
    const val BACKGROUND_IMAGE = "image"
    const val BACKGROUND_GIF = "gif"
    const val BACKGROUND_VIDEO = "video"

    /** 默认主题色在 AccentTheme.COLORS 里的下标（绿色） */
    const val DEFAULT_ACCENT_INDEX = 3

    /**
     * 是否开机自动启动接收服务。
     *
     * 默认开启：这个应用装在电视上就是当常驻投屏接收器用的，
     * 每次重启电视都要手动打开一次不符合使用场景。
     */
    fun isAutoStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, true)

    fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply()
    }

    fun getDeviceName(context: Context): String? = prefs(context).getString(KEY_DEVICE_NAME, null)
    fun setDeviceName(context: Context, name: String) = prefs(context).edit().putString(KEY_DEVICE_NAME, name).apply()
    fun isSeekByPercent(context: Context): Boolean = prefs(context).getBoolean(KEY_SEEK_USE_PERCENT, false)
    fun getRewindValue(context: Context): Float = prefs(context).getFloat(KEY_REWIND_VALUE, 10f)
    fun getForwardValue(context: Context): Float = prefs(context).getFloat(KEY_FORWARD_VALUE, 10f)
    fun getLongPressSpeed(context: Context): Float = prefs(context).getFloat(KEY_LONG_PRESS_SPEED, 3f)
    fun getControllerTimeoutMs(context: Context): Int = prefs(context).getInt(KEY_CONTROLLER_TIMEOUT_MS, 3_000)

    fun getAccentIndex(context: Context): Int =
        prefs(context).getInt(KEY_ACCENT_INDEX, DEFAULT_ACCENT_INDEX)

    fun setAccentIndex(context: Context, index: Int) =
        prefs(context).edit().putInt(KEY_ACCENT_INDEX, index).apply()

    fun getBackgroundUri(context: Context): String? = prefs(context).getString(KEY_BACKGROUND_URI, null)
    fun getBackgroundType(context: Context): String =
        prefs(context).getString(KEY_BACKGROUND_TYPE, BACKGROUND_NONE) ?: BACKGROUND_NONE

    fun setBackground(context: Context, uri: String?, type: String) {
        prefs(context).edit()
            .putString(KEY_BACKGROUND_URI, uri)
            .putString(KEY_BACKGROUND_TYPE, type)
            .apply()
    }

    fun setPlaybackControls(context: Context, byPercent: Boolean, rewind: Float, forward: Float, speed: Float, controllerTimeoutMs: Int) {
        prefs(context).edit().putBoolean(KEY_SEEK_USE_PERCENT, byPercent)
            .putFloat(KEY_REWIND_VALUE, rewind).putFloat(KEY_FORWARD_VALUE, forward)
            .putFloat(KEY_LONG_PRESS_SPEED, speed).apply()
        prefs(context).edit().putInt(KEY_CONTROLLER_TIMEOUT_MS, controllerTimeoutMs).apply()
    }

    fun reset(context: Context) { prefs(context).edit().clear().apply() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
