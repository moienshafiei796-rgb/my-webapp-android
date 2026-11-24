package com.quickfileshare.app.network

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.quickfileshare.app.model.*
import com.quickfileshare.app.security.CryptoSession
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class FileTransferEngine(
    private val context: Context,
    private val localDeviceName: String,
    private val listener: TransferListener
) {
    interface TransferListener {
        fun onPeerConnected(peer: PeerDevice)
        fun onPeerDisconnected()
        fun onIncomingOffer(offer: TransferOffer)
        fun onProgress(currentFileName: String, fileIndex: Int, totalFiles: Int, bytesTransferred: Long, totalBytes: Long, bytesPerSecond: Long)
        fun onTransferComplete(direction: String, fileName: String, size: Long, peerName: String)
        fun onError(message: String)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var cryptoSession: CryptoSession? = null
    private var dataIn: DataInputStream? = null
    private var dataOut: DataOutputStream? = null
    private val cancelFlag = AtomicBoolean(false)
    private val pendingDecisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    var listeningPort: Int = DiscoveryManager.DEFAULT_PORT
        private set

    companion object { const val CHUNK_SIZE = 256 * 1024 }

    fun startServer() {
        if (serverSocket != null && !serverSocket!!.isClosed) return
        scope.launch {
            try {
                val srv = ServerSocket(0)
                serverSocket = srv
                listeningPort = srv.localPort
                while (!srv.isClosed) {
                    val client = srv.accept()
                    client.tcpNoDelay = true; client.sendBufferSize = 1024 * 1024; client.receiveBufferSize = 1024 * 1024
                    handleNewSocket(client)
                }
            } catch (e: Exception) {
                if (serverSocket?.isClosed == false) listener.onError("Server error: ${e.message}")
            }
        }
    }

    fun connectToPeer(peer: PeerDevice) {
        scope.launch {
            try {
                closeActiveConnection()
                val socket = Socket()
                socket.tcpNoDelay = true; socket.sendBufferSize = 1024 * 1024; socket.receiveBufferSize = 1024 * 1024
                socket.connect(InetSocketAddress(peer.ip, peer.port), 8000)
                handleNewSocket(socket, peer)
            } catch (e: Exception) { listener.onError("Connection failed: ${e.message}") }
        }
    }

    private suspend fun handleNewSocket(socket: Socket, knownPeer: PeerDevice? = null) {
        activeSocket = socket
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), CHUNK_SIZE))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), CHUNK_SIZE))
        val crypto = CryptoSession()
        try {
            crypto.performHandshake(input, output)
            dataIn = input; dataOut = output; cryptoSession = crypto
            val hello = JSONObject().apply { put("cmd", "HELLO"); put("deviceName", localDeviceName); put("port", listeningPort) }
            crypto.writeEncryptedFrame(output, CryptoSession.FRAME_JSON_CONTROL, hello.toString().toByteArray())
            val (type, firstPayload) = crypto.readEncryptedFrame(input)
            val remoteName = if (type == CryptoSession.FRAME_JSON_CONTROL) JSONObject(String(firstPayload)).optString("deviceName", knownPeer?.name ?: "Peer") else knownPeer?.name ?: "Peer"
            listener.onPeerConnected(knownPeer ?: PeerDevice("${socket.inetAddress.hostAddress}:${socket.port}", remoteName, socket.inetAddress.hostAddress ?: "", socket.port))
            receiveLoop(input, output, crypto, remoteName)
        } catch (_: Exception) { listener.onPeerDisconnected() }
    }

    private suspend fun receiveLoop(input: DataInputStream, output: DataOutputStream, crypto: CryptoSession, peerName: String) {
        var outStream: OutputStream? = null
        var fileMeta: IncomingFileMeta? = null
        var offerFiles: List<IncomingFileMeta> = emptyList()
        var fileIdx = 0; var totalOfferBytes = 0L; var receivedTotal = 0L; var startMs = 0L
        try {
            while (activeSocket?.isConnected == true && !activeSocket!!.isClosed) {
                val (frameType, payload) = crypto.readEncryptedFrame(input)
                when (frameType) {
                    CryptoSession.FRAME_JSON_CONTROL -> {
                        val json = JSONObject(String(payload))
                        when (json.optString("cmd")) {
                            "OFFER" -> {
                                val offerId = json.getString("offerId")
                                val sender = json.optString("senderName", peerName)
                                val arr = json.getJSONArray("files")
                                val metas = (0 until arr.length()).map {
                                    val o = arr.getJSONObject(it)
                                    IncomingFileMeta(o.getString("name"), o.getLong("size"), o.optString("mime", "application/octet-stream"))
                                }
                                val offer = TransferOffer(offerId, sender, metas)
                                val decision = CompletableDeferred<Boolean>()
                                pendingDecisions[offerId] = decision
                                listener.onIncomingOffer(offer)
                                val accepted = decision.await()
                                pendingDecisions.remove(offerId)
                                val reply = JSONObject().apply { put("cmd", "OFFER_REPLY"); put("offerId", offerId); put("accepted", accepted) }
                                crypto.writeEncryptedFrame(output, CryptoSession.FRAME_JSON_CONTROL, reply.toString().toByteArray())
                                if (accepted) { offerFiles = metas; fileIdx = 0; totalOfferBytes = offer.totalBytes; receivedTotal = 0L; startMs = System.currentTimeMillis() }
                            }
                            "OFFER_REPLY" -> pendingDecisions[json.getString("offerId")]?.complete(json.getBoolean("accepted"))
                            "FILE_START" -> {
                                fileIdx = json.getInt("index")
                                val meta = offerFiles.getOrNull(fileIdx) ?: IncomingFileMeta(json.getString("name"), json.getLong("size"), json.optString("mime", "application/octet-stream"))
                                fileMeta = meta
                                outStream = createDownloadsOutputStream(meta.name, meta.mime)
                            }
                        }
                    }
                    CryptoSession.FRAME_FILE_CHUNK -> {
                        outStream?.write(payload)
                        receivedTotal += payload.size
                        val sec = ((System.currentTimeMillis() - startMs).coerceAtLeast(100L)) / 1000.0
                        listener.onProgress(fileMeta?.name ?: "File", fileIdx + 1, offerFiles.size.coerceAtLeast(1), receivedTotal, totalOfferBytes.coerceAtLeast(receivedTotal), (receivedTotal / sec).toLong())
                    }
                    CryptoSession.FRAME_FILE_EOF -> {
                        outStream?.flush(); outStream?.close(); outStream = null
                        fileMeta?.let { listener.onTransferComplete("IN", it.name, it.size, peerName) }
                    }
                    CryptoSession.FRAME_CANCEL -> { outStream?.close(); outStream = null; listener.onError("Transfer cancelled") }
                }
            }
        } finally { outStream?.close(); listener.onPeerDisconnected() }
    }

    fun resolveIncomingOffer(offerId: String, accept: Boolean) { pendingDecisions[offerId]?.complete(accept) }

    fun sendFiles(files: List<OutgoingFileItem>, peerName: String) {
        val out = dataOut ?: run { listener.onError("No active connection"); return }
        val crypto = cryptoSession ?: return
        cancelFlag.set(false)
        scope.launch {
            try {
                val offerId = UUID.randomUUID().toString()
                val filesJson = JSONArray()
                var grandTotal = 0L
                files.forEach { grandTotal += it.size; filesJson.put(JSONObject().apply { put("name", it.name); put("size", it.size); put("mime", it.mime) }) }
                val offerJson = JSONObject().apply { put("cmd", "OFFER"); put("offerId", offerId); put("senderName", localDeviceName); put("files", filesJson) }
                val decision = CompletableDeferred<Boolean>()
                pendingDecisions[offerId] = decision
                crypto.writeEncryptedFrame(out, CryptoSession.FRAME_JSON_CONTROL, offerJson.toString().toByteArray())
                val accepted = withTimeoutOrNull(60_000L) { decision.await() } ?: false
                pendingDecisions.remove(offerId)
                if (!accepted) { listener.onError("Receiver declined"); return@launch }

                var totalSent = 0L; val startTime = System.currentTimeMillis(); val buffer = ByteArray(CHUNK_SIZE)
                files.forEachIndexed { index, item ->
                    if (cancelFlag.get()) { crypto.writeEncryptedFrame(out, CryptoSession.FRAME_CANCEL, ByteArray(1)); return@launch }
                    val startCmd = JSONObject().apply { put("cmd", "FILE_START"); put("index", index); put("name", item.name); put("size", item.size); put("mime", item.mime) }
                    crypto.writeEncryptedFrame(out, CryptoSession.FRAME_JSON_CONTROL, startCmd.toString().toByteArray())
                    context.contentResolver.openInputStream(item.uri)?.use { stream ->
                        while (true) {
                            if (cancelFlag.get()) { crypto.writeEncryptedFrame(out, CryptoSession.FRAME_CANCEL, ByteArray(1)); return@launch }
                            val read = stream.read(buffer)
                            if (read <= 0) break
                            crypto.writeEncryptedFrame(out, CryptoSession.FRAME_FILE_CHUNK, if (read == buffer.size) buffer else buffer.copyOf(read))
                            totalSent += read
                            val sec = ((System.currentTimeMillis() - startTime).coerceAtLeast(100L)) / 1000.0
                            listener.onProgress(item.name, index + 1, files.size, totalSent, grandTotal, (totalSent / sec).toLong())
                        }
                    }
                    crypto.writeEncryptedFrame(out, CryptoSession.FRAME_FILE_EOF, ByteArray(1))
                    listener.onTransferComplete("OUT", item.name, item.size, peerName)
                }
            } catch (e: Exception) { listener.onError("Send failed: ${e.message}") }
        }
    }

    fun cancelTransfer() { cancelFlag.set(true) }

    private fun createDownloadsOutputStream(fileName: String, mimeType: String): OutputStream {
        val safeName = fileName.replace(Regex("[\\/:*?\"<>|]"), "_")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/QuickFileShare")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            return context.contentResolver.openOutputStream(uri)!!
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "QuickFileShare")
            if (!dir.exists()) dir.mkdirs()
            return FileOutputStream(File(dir, safeName))
        }
    }

    fun closeActiveConnection() { try { activeSocket?.close() } catch (_: Exception) {}; activeSocket = null; cryptoSession = null }
    fun shutdown() { closeActiveConnection(); try { serverSocket?.close() } catch (_: Exception) {}; scope.cancel() }
}
