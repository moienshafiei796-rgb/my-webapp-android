package com.quickfileshare.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.quickfileshare.app.bridge.QuickShareBridge
import com.quickfileshare.app.model.*
import com.quickfileshare.app.network.DiscoveryManager
import com.quickfileshare.app.network.FileTransferEngine
import org.json.JSONObject

class MainActivity : AppCompatActivity(), FileTransferEngine.TransferListener {

    private lateinit var webView: WebView
    private lateinit var discoveryManager: DiscoveryManager
    private lateinit var transferEngine: FileTransferEngine
    private var connectedPeerName: String = "Peer"

    private val safFilePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val items = uris.mapNotNull { resolveOutgoingFile(it) }
            if (items.isNotEmpty()) transferEngine.sendFiles(items, connectedPeerName)
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        discoveryManager.startDiscovery()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        discoveryManager = DiscoveryManager(
            context = this,
            onPeerFound = { peer -> evalJs("window.onNativePeerFound(${JSONObject.quote(peer.toJson().toString())})") },
            onPeerLost = { peerId -> evalJs("window.onNativePeerLost(${JSONObject.quote(peerId)})") },
            onWifiDirectGroupIp = { ip -> transferEngine.connectToPeer(PeerDevice("$ip:${DiscoveryManager.DEFAULT_PORT}", "Wi-Fi Direct", ip, DiscoveryManager.DEFAULT_PORT, "Wi-Fi Direct")) }
        )

        transferEngine = FileTransferEngine(this, discoveryManager.getDeviceDisplayName(), this)
        transferEngine.startServer()
        discoveryManager.registerService(transferEngine.listeningPort)
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val assetLoader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        webView.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false; allowFileAccess = false; allowContentAccess = true }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) request.grant(request.resources) else request.deny()
            }
        }
        val bridge = QuickShareBridge(
            deviceNameProvider = { discoveryManager.getDeviceDisplayName() },
            localIpProvider = { discoveryManager.getLocalIpv4Address() },
            listeningPortProvider = { transferEngine.listeningPort },
            onStartDiscovery = { requestRuntimePermissionsAndScan() },
            onStopDiscovery = { discoveryManager.stopDiscovery() },
            onConnectPeer = { peer -> if (peer.id.startsWith("p2p_")) discoveryManager.connectWifiDirectDevice(peer.ip) else transferEngine.connectToPeer(peer) },
            onDisconnect = { transferEngine.closeActiveConnection() },
            onPickFiles = { runOnUiThread { safFilePicker.launch(arrayOf("*/*")) } },
            onStartReceiver = { transferEngine.startServer(); discoveryManager.registerService(transferEngine.listeningPort) },
            onRespondOffer = { id, acc -> transferEngine.resolveIncomingOffer(id, acc) },
            onCancelTransfer = { transferEngine.cancelTransfer() },
            onConnectByQr = { raw -> handleQrConnection(raw) }
        )
        webView.addJavascriptInterface(bridge, "QuickShareNative")
        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
    }

    private fun requestRuntimePermissionsAndScan() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA)
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray()) else discoveryManager.startDiscovery()
    }

    private fun handleQrConnection(raw: String) {
        try {
            val encoded = raw.removePrefix("quickfileshare://").trim()
            val json = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
            transferEngine.connectToPeer(PeerDevice("${json.getString("ip")}:${json.getInt("port")}", json.optString("name", "QR Peer"), json.getString("ip"), json.getInt("port"), "QR Direct TCP/AES-256"))
        } catch (e: Exception) { onError("Invalid QR: ${e.message}") }
    }

    private fun resolveOutgoingFile(uri: Uri): OutgoingFileItem? {
        var name = "file_${System.currentTimeMillis()}"; var size = 0L
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) { if (nIdx >= 0) name = c.getString(nIdx) ?: name; if (sIdx >= 0) size = c.getLong(sIdx) }
        }
        return OutgoingFileItem(uri, name, size, mime)
    }

    override fun onPeerConnected(peer: PeerDevice) { connectedPeerName = peer.name; evalJs("window.onNativeConnected(${JSONObject.quote(peer.toJson().toString())})") }
    override fun onPeerDisconnected() { evalJs("window.onNativeDisconnected()") }
    override fun onIncomingOffer(offer: TransferOffer) { evalJs("window.onNativeIncomingOffer(${JSONObject.quote(offer.toJson().toString())})") }
    override fun onProgress(currentFileName: String, fileIndex: Int, totalFiles: Int, bytesTransferred: Long, totalBytes: Long, bytesPerSecond: Long) {
        val j = JSONObject().apply { put("currentFileName", currentFileName); put("fileIndex", fileIndex); put("totalFiles", totalFiles); put("bytesTransferred", bytesTransferred); put("totalBytes", totalBytes); put("bytesPerSecond", bytesPerSecond) }
        evalJs("window.onNativeTransferProgress(${JSONObject.quote(j.toString())})")
    }
    override fun onTransferComplete(direction: String, fileName: String, size: Long, peerName: String) {
        val j = JSONObject().apply { put("direction", direction); put("fileName", fileName); put("size", size); put("peerName", peerName); put("timestamp", System.currentTimeMillis()) }
        evalJs("window.onNativeTransferComplete(${JSONObject.quote(j.toString())})")
    }
    override fun onError(message: String) { evalJs("window.onNativeError(${JSONObject.quote(message)})") }
    private fun evalJs(script: String) { runOnUiThread { webView.evaluateJavascript(script, null) } }
    override fun onDestroy() { super.onDestroy(); discoveryManager.stopDiscovery(); discoveryManager.unregisterService(); transferEngine.shutdown() }
}
