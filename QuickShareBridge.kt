package com.quickfileshare.app.bridge

import android.webkit.JavascriptInterface
import com.quickfileshare.app.model.PeerDevice
import org.json.JSONObject

class QuickShareBridge(
    private val deviceNameProvider: () -> String,
    private val localIpProvider: () -> String,
    private val listeningPortProvider: () -> Int,
    private val onStartDiscovery: () -> Unit,
    private val onStopDiscovery: () -> Unit,
    private val onConnectPeer: (PeerDevice) -> Unit,
    private val onDisconnect: () -> Unit,
    private val onPickFiles: () -> Unit,
    private val onStartReceiver: () -> Unit,
    private val onRespondOffer: (String, Boolean) -> Unit,
    private val onCancelTransfer: () -> Unit,
    private val onConnectByQr: (String) -> Unit
) {
    @JavascriptInterface
    fun getDeviceInfo(): String = JSONObject().apply {
        put("deviceName", deviceNameProvider()); put("ip", localIpProvider()); put("port", listeningPortProvider())
    }.toString()

    @JavascriptInterface fun startDiscovery() = onStartDiscovery()
    @JavascriptInterface fun stopDiscovery() = onStopDiscovery()
    @JavascriptInterface fun connectToPeer(peerJson: String) = onConnectPeer(PeerDevice.fromJson(JSONObject(peerJson)))
    @JavascriptInterface fun disconnect() = onDisconnect()
    @JavascriptInterface fun pickFilesAndSend() = onPickFiles()
    @JavascriptInterface fun startReceiverServer() = onStartReceiver()
    @JavascriptInterface fun respondToOffer(offerId: String, accept: Boolean) = onRespondOffer(offerId, accept)
    @JavascriptInterface fun cancelTransfer() = onCancelTransfer()

    @JavascriptInterface
    fun getConnectionQrPayload(): String {
        val obj = JSONObject().apply {
            put("proto", "quickfileshare-v1"); put("name", deviceNameProvider()); put("ip", localIpProvider()); put("port", listeningPortProvider())
        }
        return "quickfileshare://" + android.util.Base64.encodeToString(obj.toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    }

    @JavascriptInterface fun connectByQrPayload(rawPayload: String) = onConnectByQr(rawPayload)
}
