package com.weekd.miracastreceiver.util

import android.app.Activity
import android.content.Context
import android.graphics.Color
import com.weekd.miracastreceiver.R

/**
 * 用户可选的主题色。在 Activity 的 onCreate 里、setContentView 之前调用 [apply]，
 * 把对应的主题 overlay 叠到当前主题上；drawable 里通过 ?attr/colorAccent 引用，
 * 从而实现整页换色而不需要逐控件改代码。
 */
object AccentTheme {

    // 与 R.array.theme_color_options 顺序一一对应
    private val COLORS = intArrayOf(
        0xFFF44336.toInt(), // 红
        0xFFFF9800.toInt(), // 橙
        0xFFFDD835.toInt(), // 黄
        0xFF00C853.toInt(), // 绿（默认）
        0xFF40C4FF.toInt(), // 浅蓝
        0xFF1E88E5.toInt(), // 蓝
        0xFF3949AB.toInt(), // 深蓝
        0xFFF06292.toInt(), // 粉
        0xFFAB47BC.toInt(), // 紫
    )

    private val OVERLAYS = intArrayOf(
        R.style.ThemeAccentRed,
        R.style.ThemeAccentOrange,
        R.style.ThemeAccentYellow,
        R.style.ThemeAccentGreen,
        R.style.ThemeAccentLightBlue,
        R.style.ThemeAccentBlue,
        R.style.ThemeAccentDeepBlue,
        R.style.ThemeAccentPink,
        R.style.ThemeAccentPurple,
    )

    // 主题色上压文字时用深色还是白色：浅色主题色（橙/黄/绿/浅蓝/粉）压深字，其余压白字
    private val ON_ACCENT_DARK = booleanArrayOf(false, true, true, true, true, false, false, true, false)

    fun index(context: Context): Int =
        AppSettings.getAccentIndex(context).coerceIn(0, COLORS.lastIndex)

    /** 在 setContentView 之前调用。 */
    fun apply(activity: Activity) {
        activity.theme.applyStyle(OVERLAYS[index(activity)], true)
    }

    fun currentColor(context: Context): Int = COLORS[index(context)]

    /** 压在主题色底色上的文字颜色。 */
    fun onAccentTextColor(context: Context): Int =
        if (ON_ACCENT_DARK[index(context)]) 0xFF0B1424.toInt() else Color.WHITE
}
