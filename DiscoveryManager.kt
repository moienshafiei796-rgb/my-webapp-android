package com.quickfileshare.app.network

import android.annotation.SuppressLint
import android.content.*
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Looper
import com.quickfileshare.app.model.PeerDevice
import java.net.Inet4Address
import java.net.NetworkInterface

class DiscoveryManager(
    private val context: Context,
    private val onPeerFound: (PeerDevice) -> Unit,
    private val onPeerLost: (String) -> Unit,
    private val onWifiDirectGroupIp: (String) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false
    private var p2pReceiver: BroadcastReceiver? = null

    companion object {
        const val SERVICE_TYPE = "_quickfileshare._tcp."
        const val DEFAULT_PORT = 45890
    }

    fun getDeviceDisplayName(): String {
        val m = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return if (Build.MODEL.startsWith(m, ignoreCase = true)) Build.MODEL else "$m ${Build.MODEL}"
    }

    fun getLocalIpv4Address(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return ip
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun registerService(port: Int) {
        unregisterService()
        val info = NsdServiceInfo().apply {
            serviceName = "QuickFileShare-${getDeviceDisplayName()}"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {}
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
        }
        try { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener) } catch (_: Exception) {}
    }

    fun unregisterService() {
        registrationListener?.let { try { nsdManager.unregisterService(it) } catch (_: Exception) {} }
        registrationListener = null
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (isDiscovering) return
        isDiscovering = true
        val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiMgr?.createMulticastLock("QuickFileShareMulticast")?.apply { setReferenceCounted(true); acquire() }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("quickfileshare")) return
                if (service.serviceName.contains(getDeviceDisplayName())) return
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val hostIp = resolved.host?.hostAddress ?: return
                        onPeerFound(PeerDevice("${hostIp}:${resolved.port}", resolved.serviceName.removePrefix("QuickFileShare-"), hostIp, resolved.port, "Wi-Fi LAN (AES-256)"))
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) { onPeerLost(service.serviceName) }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { isDiscovering = false }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        try { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) } catch (_: Exception) {}
        startWifiDirectDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun startWifiDirectDiscovery() {
        val mgr = wifiP2pManager ?: return
        p2pChannel = mgr.initialize(context, Looper.getMainLooper(), null)
        p2pReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> mgr.requestPeers(p2pChannel) { list ->
                        for (dev in list.deviceList) onPeerFound(PeerDevice("p2p_${dev.deviceAddress}", dev.deviceName, dev.deviceAddress, DEFAULT_PORT, "Wi-Fi Direct"))
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> mgr.requestConnectionInfo(p2pChannel) { info ->
                        if (info.groupFormed && !info.isGroupOwner) info.groupOwnerAddress?.hostAddress?.let { onWifiDirectGroupIp(it) }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(p2pReceiver, filter)
        try { mgr.discoverPeers(p2pChannel, null) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun connectWifiDirectDevice(mac: String) {
        val mgr = wifiP2pManager ?: return; val ch = p2pChannel ?: return
        mgr.connect(ch, WifiP2pConfig().apply { deviceAddress = mac }, null)
    }

    fun stopDiscovery() {
        isDiscovering = false
        discoveryListener?.let { try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discoveryListener = null
        p2pReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        p2pReceiver = null
        if (multicastLock?.isHeld == true) multicastLock?.release()
    }
}
