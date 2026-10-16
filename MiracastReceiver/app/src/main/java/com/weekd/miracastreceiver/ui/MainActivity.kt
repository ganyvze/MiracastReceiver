package com.weekd.miracastreceiver.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.discovery.DeviceInfoProvider
import com.weekd.miracastreceiver.discovery.MdnsAdvertiser
import com.weekd.miracastreceiver.service.CastReceiverService
import com.weekd.miracastreceiver.util.AccentTheme
import com.weekd.miracastreceiver.util.AppSettings
import com.weekd.miracastreceiver.utils.NetworkUtils
import timber.log.Timber
import android.widget.TextView
import android.widget.Button
import android.text.SpannableString
import android.text.style.ForegroundColorSpan

/**
 * 主 Activity - 等待投屏连接
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mdnsAdvertiser: MdnsAdvertiser
    private lateinit var deviceInfoProvider: DeviceInfoProvider

    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceIp: TextView
    private lateinit var tvWifiName: TextView

    private var connectionCode: String = ""

    /** 页面创建时应用的主题色/背景快照，用于从设置页返回时判断是否需要 recreate */
    private var appliedAccentIndex = -1
    private var appliedBackgroundSignature = ""
    private lateinit var backdrop: PageBackdrop

    /** 定位权限弹窗还在时不叠加悬浮窗权限提示，等它有结果再说 */
    private var wifiPermissionPending = false
    private var overlayPromptShown = false

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private const val REQUEST_SETTINGS = 1002

        /**
         * Wi-Fi Direct 需要的危险权限，必须运行时申请。
         *
         * 少了它 `WifiP2pManager.createGroup()` 会抛 SecurityException，Miracast 直接不可用
         * （AirPlay / DLNA 不受影响）。读取 Wi-Fi 名称仍需定位权限，因此两个权限都申请。
         */
        private val WIFI_DIRECT_PERMISSIONS: Array<String>
            get() = if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                // Android 12+ 只申请 FINE 会被系统直接拒绝，必须两个一起申请
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        backdrop = PageBackdrop(this, findViewById(R.id.backdrop_container), findViewById(R.id.backdrop_scrim))
        backdrop.apply()
        appliedAccentIndex = AppSettings.getAccentIndex(this)
        appliedBackgroundSignature = backgroundSignature()

        initServices()
        initViews()
        requestWifiDirectPermissions()
        checkNetworkAndStart()
    }

    private fun backgroundSignature(): String =
        "${AppSettings.getBackgroundType(this)}|${AppSettings.getBackgroundUri(this)}"

    private fun requestWifiDirectPermissions() {
        val missing = WIFI_DIRECT_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Timber.i("Requesting Wi-Fi Direct permissions: $missing")
            wifiPermissionPending = true
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return
        wifiPermissionPending = false

        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            // 权限是在服务启动之后才拿到的，重启一次服务让 Wi-Fi Direct 重新初始化
            Timber.i("Wi-Fi Direct permissions granted, restarting cast service")
            startCastService()
            refreshWifiName()
        } else {
            Timber.w("Wi-Fi Direct permissions denied — Miracast unavailable")
            Toast.makeText(this, "未授予定位权限，Windows 无线投屏不可用", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::tvWifiName.isInitialized) refreshWifiName()
        if (!wifiPermissionPending) promptOverlayPermissionIfNeeded()
    }

    /**
     * 「显示在其他应用上层」权限。
     *
     * Android 10 起系统禁止后台启动 Activity，前台服务也不例外。开机自启或者用户按了
     * 主页键之后，应用不在前台，投屏连上时服务拉起播放页会被系统静默拦截 —— 声音、
     * 解码都在跑，屏幕上却什么都没有。持有这个权限的应用可以豁免该限制。
     */
    private fun promptOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (overlayPromptShown || Settings.canDrawOverlays(this)) return
        overlayPromptShown = true

        AlertDialog.Builder(this)
            .setTitle("需要「显示在其他应用上层」权限")
            .setMessage(
                "未打开本应用时（例如开机自启后、或退回桌面后），系统会阻止投屏画面自动弹出，" +
                    "表现为投屏没有反应。\n\n请在接下来的设置页中为本应用开启此权限。"
            )
            .setPositiveButton("去设置") { _, _ -> openOverlaySettings() }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun openOverlaySettings() {
        val withPackage = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        // 部分电视的设置页不认带包名的 Intent，退回到列表页让用户自己找
        val intents = listOf(withPackage, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Timber.w("Overlay settings not available: ${intent.data}")
            }
        }
        Timber.w("No overlay permission settings page on this device")
        Toast.makeText(
            this,
            "此设备没有该设置页，请用 adb 执行：appops set $packageName SYSTEM_ALERT_WINDOW allow",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun initServices() {
        deviceInfoProvider = DeviceInfoProvider(this)
        mdnsAdvertiser = MdnsAdvertiser(this)

        // 生成连接码
        connectionCode = NetworkUtils.generateConnectionCode()
    }

    private fun initViews() {
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvDeviceIp = findViewById(R.id.tv_device_ip)
        tvWifiName = findViewById(R.id.tv_wifi_name)
        refreshWifiName()
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            // 延迟一拍再跳转，让按钮松开的回弹动画先播出来
            it.postDelayed({
                startActivityForResult(Intent(this, SettingsActivity::class.java), REQUEST_SETTINGS)
            }, 200)
        }

        // 设置设备名称
        val deviceName = deviceInfoProvider.getDeviceName()
        tvDeviceName.text = deviceName
    }

    private fun checkNetworkAndStart() {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            Toast.makeText(this, "网络未连接", Toast.LENGTH_LONG).show()
            Timber.w("Network not available")
            return
        }

        if (!NetworkUtils.isWifiConnected(this)) {
            Toast.makeText(this, "未连接到 Wi-Fi", Toast.LENGTH_LONG).show()
            Timber.w("WiFi not connected")
        }

        // 获取 IP 地址
        val ipAddress = NetworkUtils.getLocalIpAddress()
        if (ipAddress != null) {
            tvDeviceIp.text = getString(R.string.device_ip, ipAddress)
            Timber.i("Local IP: $ipAddress")
        } else {
            tvDeviceIp.text = getString(R.string.device_ip, "获取中...")
        }
        // 启动服务
        startCastService()
        startAdvertising()

    }

    private fun refreshWifiName() {
        val ssid = NetworkUtils.getWifiSSID(this)
        val name = when {
            !ssid.isNullOrBlank() -> ssid
            NetworkUtils.isWifiConnected(this) -> "已连接（名称不可用）"
            else -> "未连接"
        }
        val prefix = "Wi-Fi："
        tvWifiName.text = SpannableString(prefix + name).apply {
            setSpan(ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, R.color.text_secondary)), 0, prefix.length, 0)
            setSpan(ForegroundColorSpan(AccentTheme.currentColor(this@MainActivity)), prefix.length, length, 0)
        }
    }

    private fun startCastService() {
        val intent = Intent(this, CastReceiverService::class.java)
        startService(intent)
        Timber.i("Cast receiver service started")
    }

    private fun startAdvertising() {
        val deviceInfo = deviceInfoProvider.getDeviceInfo()
        val deviceName = deviceInfoProvider.getDeviceName()
        val macAddress = NetworkUtils.getMacAddress()

        // AirPlay is advertised by CastReceiverService/AirPlayReceiver only.
        // Do not register a second _airplay._tcp service here: duplicate AirPlay mDNS
        // records with different TXT capabilities can make iOS connect with the wrong
        // protocol path and never send the mirroring video SETUP.

        // 启动自定义 Miracast 服务广播（用于自定义 Android 发送端）
        mdnsAdvertiser.startAdvertising(
            serviceName = deviceName,
            port = 8080,
            deviceInfo = deviceInfo + ("code" to connectionCode)
        )

        Timber.i("Started AirPlay and Miracast advertising: $deviceName")
    }

    override fun onDestroy() {
        super.onDestroy()
        mdnsAdvertiser.stopAdvertising()
        Timber.i("MainActivity destroyed")
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK) {
            // 主题色或背景变了：直接 recreate 整页重建（服务启动逻辑在 onCreate 里会重跑）
            if (AppSettings.getAccentIndex(this) != appliedAccentIndex ||
                backgroundSignature() != appliedBackgroundSignature
            ) {
                recreate()
                return
            }
            tvDeviceName.text = deviceInfoProvider.getDeviceName()
            mdnsAdvertiser.stopAdvertising()
            stopService(Intent(this, CastReceiverService::class.java))
            startCastService()
            startAdvertising()
        }
    }
}
