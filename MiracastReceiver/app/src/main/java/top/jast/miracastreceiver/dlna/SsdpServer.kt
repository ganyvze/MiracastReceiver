package top.jast.miracastreceiver.dlna

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.Collections

/**
 * SSDP (Simple Service Discovery Protocol) 服务器
 * 用于 DLNA/UPnP 设备发现
 */
class SsdpServer(
    private val context: Context,
    private val deviceUuid: String,
    private val localIp: String,
    private val httpPort: Int = 8080
) {
    companion object {
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SSDP_SEARCH_PATTERN = "M-SEARCH"

        // UPnP Device Types
        private const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val SERVICE_TYPE_AV = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val SERVICE_TYPE_RC = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val SERVICE_TYPE_CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
    }

    private var multicastSocket: MulticastSocket? = null
    private var serverJob: Job? = null
    private var refreshJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * 不持有 MulticastLock 时，Android 的 Wi-Fi 栈（省电模式尤其明显）会直接丢弃
     * 组播帧，SSDP 的 M-SEARCH 收不到，DLNA 客户端就搜不到本机。
     */
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (serverJob?.isActive == true) {
            Timber.w("SSDP server already running")
            return
        }

        serverJob = scope.launch {
            try {
                val group = InetAddress.getByName(SSDP_ADDRESS)
                val networkInterface = findLocalInterface()

                // reuseAddress 必须在 bind 之前设置，否则端口复用时 bind 会失败
                val socket = MulticastSocket(null as SocketAddress?).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(SSDP_PORT))
                    timeToLive = 2
                    if (networkInterface != null) {
                        this.networkInterface = networkInterface
                    }
                }

                // 显式在 Wi-Fi 接口上加入组播组：开了 Wi-Fi Direct 后默认接口可能是 p2p0，
                // 用 null 会让 M-SEARCH 落到错误的网卡上
                if (networkInterface != null) {
                    socket.joinGroup(InetSocketAddress(group, SSDP_PORT), networkInterface)
                } else {
                    socket.joinGroup(InetSocketAddress(group, SSDP_PORT), null as NetworkInterface?)
                }

                multicastSocket = socket
                acquireMulticastLock()

                Timber.i("SSDP server started on port $SSDP_PORT (iface=${networkInterface?.name ?: "default"})")

                val buffer = ByteArray(1024)
                while (multicastSocket?.isClosed == false) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicastSocket?.receive(packet)

                    val message = String(packet.data, 0, packet.length)
                    if (message.contains(SSDP_SEARCH_PATTERN)) {
                        handleSearchRequest(message, packet.address, packet.port)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "SSDP server error")
            }
        }

        // 启动定期广播
        startPeriodicNotify()
    }

    /** 找到 localIp 所在的网卡，多网卡（wlan0 / p2p0 / eth0）时保证组播走 Wi-Fi。 */
    private fun findLocalInterface(): NetworkInterface? {
        return try {
            val target = InetAddress.getByName(localIp)
            Collections.list(NetworkInterface.getNetworkInterfaces()).firstOrNull { nif ->
                nif.isUp && !nif.isLoopback &&
                    Collections.list(nif.inetAddresses).any { it == target }
            }
        } catch (e: Exception) {
            Timber.w(e, "Cannot resolve network interface for $localIp")
            null
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifiManager.createMulticastLock("miracast-dlna-ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Timber.w(e, "Cannot acquire multicast lock")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Timber.w(e, "Cannot release multicast lock")
        }
        multicastLock = null
    }

    fun stop() {
        refreshJob?.cancel()
        try {
            sendByebye()
            serverJob?.cancel()
            multicastSocket?.close()
            releaseMulticastLock()
            Timber.i("SSDP server stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping SSDP server")
        }
    }

    private fun handleSearchRequest(message: String, address: InetAddress, port: Int) {
        Timber.d("SSDP search from ${address.hostAddress}:$port")

        // 检查搜索目标
        val searchTarget = extractSearchTarget(message)

        val shouldRespond = when {
            searchTarget == null -> false
            searchTarget == "ssdp:all" -> true
            searchTarget == "upnp:rootdevice" -> true
            searchTarget.contains("MediaRenderer") -> true
            searchTarget.contains("AVTransport") -> true
            searchTarget.contains("RenderingControl") -> true
            searchTarget.contains("ConnectionManager") -> true
            searchTarget == "uuid:$deviceUuid" -> true
            else -> false
        }

        if (shouldRespond) {
            sendSearchResponse(address, port, searchTarget ?: "upnp:rootdevice")
        }
    }

    private fun extractSearchTarget(message: String): String? {
        // 兼容只发 \n 换行的控制点（部分 App 不按规范发 \r\n）
        for (line in message.lineSequence()) {
            if (line.startsWith("ST:", ignoreCase = true)) {
                return line.substring(3).trim()
            }
        }
        return null
    }

    private fun sendSearchResponse(address: InetAddress, port: Int, searchTarget: String) {
        val location = "http://$localIp:$httpPort/device.xml"

        // M-SEARCH 的 ST 为 ssdp:all 时必须逐个目标回复（rootdevice / uuid / 设备类型 / 各服务），
        // 直接把 ST 回成 ssdp:all 不符合 UPnP 规范，严格的客户端会丢弃这条响应导致搜不到设备
        val targets = if (searchTarget == "ssdp:all") {
            listOf(
                "upnp:rootdevice",
                "uuid:$deviceUuid",
                DEVICE_TYPE,
                SERVICE_TYPE_AV,
                SERVICE_TYPE_RC,
                SERVICE_TYPE_CM
            )
        } else {
            listOf(searchTarget)
        }

        try {
            val socket = createNotifySocket()
            for (target in targets) {
                val response = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("CACHE-CONTROL: max-age=1800\r\n")
                    append("EXT:\r\n")
                    append("LOCATION: $location\r\n")
                    append("SERVER: Android/11 UPnP/1.0 MiracastReceiver/1.0\r\n")
                    append("ST: $target\r\n")
                    append("USN: uuid:$deviceUuid")

                    if (target != "uuid:$deviceUuid") {
                        append("::$target")
                    }

                    append("\r\n\r\n")
                }

                val data = response.toByteArray()
                socket.send(DatagramPacket(data, data.size, address, port))
            }
            socket.close()
            Timber.d("Sent SSDP response to ${address.hostAddress}:$port (${targets.size} targets)")
        } catch (e: Exception) {
            Timber.e(e, "Error sending SSDP response")
        }
    }

    /** 组播/NOTIFY 用套接字：绑到 localIp 所属网卡，避免多网卡时从错误的接口发出去。 */
    private fun createNotifySocket(): DatagramSocket {
        return try {
            DatagramSocket(0, InetAddress.getByName(localIp))
        } catch (e: Exception) {
            DatagramSocket()
        }
    }

    private fun startPeriodicNotify() {
        scope.launch {
            try {
                kotlinx.coroutines.delay(2000) // 启动后等待2秒
                sendNotify()

                // 每30分钟发送一次 NOTIFY
                while (true) {
                    kotlinx.coroutines.delay(30 * 60 * 1000)
                    sendNotify()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error in periodic notify")
            }
        }
    }

    /** Ask clients to reload device.xml after the friendly name changes. */
    fun refreshAdvertisement() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            sendByebye()
            kotlinx.coroutines.delay(250)
            sendNotify()
        }
    }

    private fun sendNotify() {
        val location = "http://$localIp:$httpPort/device.xml"

        val notifications = listOf(
            "upnp:rootdevice",
            "uuid:$deviceUuid",
            DEVICE_TYPE,
            SERVICE_TYPE_AV,
            SERVICE_TYPE_RC,
            SERVICE_TYPE_CM
        )

        try {
            val socket = createNotifySocket()
            val group = InetAddress.getByName(SSDP_ADDRESS)

            for (nt in notifications) {
                val message = buildString {
                    append("NOTIFY * HTTP/1.1\r\n")
                    append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                    append("CACHE-CONTROL: max-age=1800\r\n")
                    append("LOCATION: $location\r\n")
                    append("NT: $nt\r\n")
                    append("NTS: ssdp:alive\r\n")
                    append("SERVER: Android/11 UPnP/1.0 MiracastReceiver/1.0\r\n")
                    append("USN: uuid:$deviceUuid")

                    if (nt != "uuid:$deviceUuid") {
                        append("::$nt")
                    }

                    append("\r\n\r\n")
                }

                val data = message.toByteArray()
                val packet = DatagramPacket(data, data.size, group, SSDP_PORT)
                socket.send(packet)
            }

            socket.close()
            Timber.i("Sent SSDP NOTIFY messages")
        } catch (e: Exception) {
            Timber.e(e, "Error sending SSDP notify")
        }
    }

    private fun sendByebye() {
        val notifications = listOf(
            "upnp:rootdevice",
            "uuid:$deviceUuid",
            DEVICE_TYPE,
            SERVICE_TYPE_AV,
            SERVICE_TYPE_RC,
            SERVICE_TYPE_CM
        )

        try {
            val socket = createNotifySocket()
            val group = InetAddress.getByName(SSDP_ADDRESS)

            for (nt in notifications) {
                val message = buildString {
                    append("NOTIFY * HTTP/1.1\r\n")
                    append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                    append("NT: $nt\r\n")
                    append("NTS: ssdp:byebye\r\n")
                    append("USN: uuid:$deviceUuid")

                    if (nt != "uuid:$deviceUuid") {
                        append("::$nt")
                    }

                    append("\r\n\r\n")
                }

                val data = message.toByteArray()
                val packet = DatagramPacket(data, data.size, group, SSDP_PORT)
                socket.send(packet)
            }

            socket.close()
            Timber.i("Sent SSDP byebye messages")
        } catch (e: Exception) {
            Timber.e(e, "Error sending SSDP byebye")
        }
    }
}
