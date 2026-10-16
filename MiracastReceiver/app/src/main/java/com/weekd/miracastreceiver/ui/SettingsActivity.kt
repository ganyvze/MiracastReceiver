package com.weekd.miracastreceiver.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.discovery.DeviceInfoProvider
import com.weekd.miracastreceiver.util.AccentTheme
import com.weekd.miracastreceiver.util.AppSettings
import timber.log.Timber

class SettingsActivity : AppCompatActivity() {
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 5f)
    private val controllerTimeoutsMs = intArrayOf(3_000, 5_000, 8_000, 10_000, 15_000)
    private lateinit var nameInput: EditText
    private lateinit var rewindInput: EditText
    private lateinit var forwardInput: EditText
    private lateinit var secondsMode: RadioButton
    private lateinit var percentMode: RadioButton
    private lateinit var seekHint: TextView
    private lateinit var speedSpinner: Spinner
    private lateinit var controllerTimeoutSpinner: Spinner
    private lateinit var themeColorSpinner: Spinner
    private lateinit var backgroundHint: TextView
    private lateinit var autoStart: SwitchCompat
    private lateinit var backdrop: PageBackdrop

    // 待提交的背景选择；hasPendingBackground=false 表示用户没动过背景设置
    private var hasPendingBackground = false
    private var pendingBackgroundUri: String? = null
    private var pendingBackgroundType: String = AppSettings.BACKGROUND_NONE

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onBackgroundPicked(it, isVideo = false) }
        }
    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onBackgroundPicked(it, isVideo = true) }
        }

    private companion object {
        // 与 btn_press_scale 回弹动画时长相配：动作延迟到动画主体播完再执行
        const val CLICK_ACTION_DELAY_MS = 200L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        backdrop = PageBackdrop(this, findViewById(R.id.backdrop_container), findViewById(R.id.backdrop_scrim))
        backdrop.apply()
        nameInput = findViewById(R.id.input_device_name); rewindInput = findViewById(R.id.input_rewind)
        forwardInput = findViewById(R.id.input_forward); secondsMode = findViewById(R.id.radio_seek_seconds)
        percentMode = findViewById(R.id.radio_seek_percent); seekHint = findViewById(R.id.tv_seek_hint)
        speedSpinner = findViewById(R.id.spinner_long_press_speed); controllerTimeoutSpinner = findViewById(R.id.spinner_controller_timeout)
        themeColorSpinner = findViewById(R.id.spinner_theme_color); backgroundHint = findViewById(R.id.tv_background_hint)
        autoStart = findViewById(R.id.switch_auto_start)
        applyAccentToControls()
        loadSettings()
        findViewById<RadioGroup>(R.id.group_seek_mode).setOnCheckedChangeListener { _, _ -> updateSeekHint() }
        findViewById<Button>(R.id.btn_pick_background).setOnClickListener {
            it.postDelayed({ showBackgroundPicker() }, CLICK_ACTION_DELAY_MS)
        }
        // 松开 OK 键会立刻执行动作，页面跳转/弹窗会盖掉回弹动画；延迟一拍让动画先播出来。
        findViewById<Button>(R.id.btn_apply).setOnClickListener { it.postDelayed({ applySettings() }, CLICK_ACTION_DELAY_MS) }
        findViewById<Button>(R.id.btn_reset).setOnClickListener { it.postDelayed({ confirmReset() }, CLICK_ACTION_DELAY_MS) }
    }

    /** 主题色底上压的文字颜色随主题变化：主按钮文字、分段控件选中项文字。 */
    private fun applyAccentToControls() {
        val onAccent = AccentTheme.onAccentTextColor(this)
        findViewById<Button>(R.id.btn_apply).setTextColor(onAccent)
        val radioTextColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(onAccent, ContextCompat.getColor(this, R.color.text_primary))
        )
        secondsMode.setTextColor(radioTextColors)
        percentMode.setTextColor(radioTextColors)
    }

    private fun loadSettings() {
        nameInput.setText(AppSettings.getDeviceName(this) ?: DeviceInfoProvider(this).getDeviceName())
        rewindInput.setText(AppSettings.getRewindValue(this).toString()); forwardInput.setText(AppSettings.getForwardValue(this).toString())
        if (AppSettings.isSeekByPercent(this)) percentMode.isChecked = true else secondsMode.isChecked = true
        val savedSpeed = AppSettings.getLongPressSpeed(this)
        val speedIndex = speeds.indices.firstOrNull { speeds[it] == savedSpeed } ?: 6
        speedSpinner.setSelection(speedIndex)
        val timeoutIndex = controllerTimeoutsMs.indices.firstOrNull { controllerTimeoutsMs[it] == AppSettings.getControllerTimeoutMs(this) } ?: 1
        controllerTimeoutSpinner.setSelection(timeoutIndex)
        themeColorSpinner.setSelection(AppSettings.getAccentIndex(this))
        autoStart.isChecked = AppSettings.isAutoStartOnBoot(this)
        hasPendingBackground = false
        updateBackgroundHint()
        updateSeekHint()
    }

    private fun updateSeekHint() { seekHint.text = if (percentMode.isChecked) "每次按左 / 右键按媒体总时长的百分比跳转" else "每次按左 / 右键快退或快进的秒数" }

    private fun showBackgroundPicker() {
        val options = arrayOf("默认背景", "选择图片 / 动图", "选择视频")
        AlertDialog.Builder(this)
            .setTitle("主页背景")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        pendingBackgroundUri = null
                        pendingBackgroundType = AppSettings.BACKGROUND_NONE
                        hasPendingBackground = true
                        updateBackgroundHint()
                    }
                    1 -> pickImageLauncher.launch(arrayOf("image/*"))
                    2 -> pickVideoLauncher.launch(arrayOf("video/*"))
                }
            }
            .show()
    }

    private fun onBackgroundPicked(uri: Uri, isVideo: Boolean) {
        // 持久化读权限，否则重启后 SAF URI 可能失效
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Timber.w("Persistable URI permission not supported: $uri")
        }
        pendingBackgroundUri = uri.toString()
        pendingBackgroundType = when {
            isVideo -> AppSettings.BACKGROUND_VIDEO
            contentResolver.getType(uri)?.contains("gif") == true -> AppSettings.BACKGROUND_GIF
            else -> AppSettings.BACKGROUND_IMAGE
        }
        hasPendingBackground = true
        updateBackgroundHint()
    }

    private fun updateBackgroundHint() {
        val uri = if (hasPendingBackground) pendingBackgroundUri else AppSettings.getBackgroundUri(this)
        val type = if (hasPendingBackground) pendingBackgroundType else AppSettings.getBackgroundType(this)
        if (type == AppSettings.BACKGROUND_NONE || uri == null) {
            backgroundHint.text = "当前：默认背景"
            return
        }
        val typeLabel = when (type) {
            AppSettings.BACKGROUND_VIDEO -> "视频"
            AppSettings.BACKGROUND_GIF -> "动图"
            else -> "图片"
        }
        backgroundHint.text = "当前：$typeLabel · ${queryDisplayName(Uri.parse(uri))}"
    }

    private fun queryDisplayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun applySettings() {
        val name = nameInput.text.toString().trim()
        val rewind = rewindInput.text.toString().toFloatOrNull()
        val forward = forwardInput.text.toString().toFloatOrNull()
        if (name.isEmpty() || rewind == null || forward == null || rewind <= 0f || forward <= 0f) {
            Toast.makeText(this, "请填写有效的设备名称、快退和快进数值", Toast.LENGTH_SHORT).show(); return
        }
        AppSettings.setDeviceName(this, name)
        AppSettings.setPlaybackControls(this, percentMode.isChecked, rewind, forward, speeds[speedSpinner.selectedItemPosition], controllerTimeoutsMs[controllerTimeoutSpinner.selectedItemPosition])
        AppSettings.setAutoStartOnBoot(this, autoStart.isChecked)
        AppSettings.setAccentIndex(this, themeColorSpinner.selectedItemPosition)
        if (hasPendingBackground) AppSettings.setBackground(this, pendingBackgroundUri, pendingBackgroundType)
        setResult(RESULT_OK); finish()
    }

    private fun confirmReset() = AlertDialog.Builder(this).setTitle("恢复默认设置？")
        .setMessage("设备名称、播放控制、主题色、背景和开机自启设置都会恢复默认值。")
        .setNegativeButton("取消", null).setPositiveButton("恢复") { _, _ -> AppSettings.reset(this); loadSettings() }.show()
}
