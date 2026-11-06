package com.zzu.lite.di

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import okhttp3.Dns

/**
 * 校园网场景专用：设备同时连着"未认证的校园 Wi-Fi"和移动数据时，
 * Android 会判定 Wi-Fi"无互联网"并把默认路由切到蜂窝，导致校内服务（教务等）不可达。
 *
 * 这里通过自定义 SocketFactory/Dns 把应用的所有请求强制绑到 Wi-Fi 网络：
 * 校内服务在校园网内免认证直达；公网服务（CAS 等）走校园网 NAT 出去同样可达。
 * 无 Wi-Fi 时回退系统默认行为（走蜂窝）。
 */
class WifiPreferringSocketFactory(private val cm: ConnectivityManager) : SocketFactory() {

    private fun wifiNetwork(): Network? = cm.allNetworks.firstOrNull { n ->
        val caps = cm.getNetworkCapabilities(n)
        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun delegate(): SocketFactory? = wifiNetwork()?.socketFactory

    override fun createSocket(): Socket = delegate()?.createSocket() ?: Socket()

    override fun createSocket(host: String, port: Int): Socket =
        delegate()?.createSocket(host, port) ?: Socket(host, port)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        delegate()?.createSocket(host, port, localHost, localPort) ?: Socket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        delegate()?.createSocket(host, port) ?: Socket(host, port)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        delegate()?.createSocket(address, port, localAddress, localPort)
            ?: Socket(address, port, localAddress, localPort)
}

class WifiPreferringDns(private val cm: ConnectivityManager) : Dns {

    private fun wifiNetwork(): Network? = cm.allNetworks.firstOrNull { n ->
        val caps = cm.getNetworkCapabilities(n)
        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val network = wifiNetwork() ?: return Dns.SYSTEM.lookup(hostname)
        return runCatching { network.getAllByName(hostname).toList() }
            .getOrElse { Dns.SYSTEM.lookup(hostname) }
    }
}
