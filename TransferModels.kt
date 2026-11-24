package com.quickfileshare.app.model

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class PeerDevice(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    val transport: String = "LAN-TCP/AES256"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("ip", ip); put("port", port); put("transport", transport)
    }
    companion object {
        fun fromJson(json: JSONObject): PeerDevice = PeerDevice(
            id = json.optString("id", ""),
            name = json.optString("name", "Android Device"),
            ip = json.optString("ip", ""),
            port = json.optInt("port", 45890),
            transport = json.optString("transport", "LAN-TCP/AES256")
        )
    }
}

data class OutgoingFileItem(val uri: Uri, val name: String, val size: Long, val mime: String)

data class IncomingFileMeta(val name: String, val size: Long, val mime: String) {
    fun toJson(): JSONObject = JSONObject().apply { put("name", name); put("size", size); put("mime", mime) }
}

data class TransferOffer(val offerId: String, val senderName: String, val files: List<IncomingFileMeta>) {
    val totalBytes: Long get() = files.sumOf { it.size }
    fun toJson(): JSONObject = JSONObject().apply {
        put("offerId", offerId); put("senderName", senderName); put("fileCount", files.size); put("totalBytes", totalBytes)
        val arr = JSONArray(); files.forEach { arr.put(it.toJson()) }; put("files", arr)
    }
}
