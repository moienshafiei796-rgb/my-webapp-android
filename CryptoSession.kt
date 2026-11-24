package com.quickfileshare.app.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoSession {
    private val keyPair: KeyPair
    private var aesKey: SecretKey? = null
    private val secureRandom = SecureRandom()

    init {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        keyPair = kpg.generateKeyPair()
    }

    fun performHandshake(input: DataInputStream, output: DataOutputStream) {
        val myPubBytes = keyPair.public.encoded
        output.writeInt(myPubBytes.size)
        output.write(myPubBytes)
        output.flush()

        val peerLen = input.readInt()
        require(peerLen in 32..2048) { "Invalid ECDH public key length" }
        val peerPubBytes = ByteArray(peerLen)
        input.readFully(peerPubBytes)

        val peerPublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPubBytes))
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(keyPair.private)
        keyAgreement.doPhase(peerPublicKey, true)
        val derivedKeyBytes = MessageDigest.getInstance("SHA-256").digest(keyAgreement.generateSecret())
        aesKey = SecretKeySpec(derivedKeyBytes, "AES")
    }

    fun writeEncryptedFrame(output: DataOutputStream, type: Byte, payload: ByteArray) {
        val key = aesKey ?: throw IllegalStateException("Handshake incomplete")
        val iv = ByteArray(12); secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(byteArrayOf(type))
        val ciphertext = cipher.doFinal(payload)
        synchronized(output) {
            output.writeByte(type.toInt()); output.write(iv); output.writeInt(ciphertext.size); output.write(ciphertext); output.flush()
        }
    }

    fun readEncryptedFrame(input: DataInputStream): Pair<Byte, ByteArray> {
        val key = aesKey ?: throw IllegalStateException("Handshake incomplete")
        val type = input.readByte()
        val iv = ByteArray(12); input.readFully(iv)
        val len = input.readInt()
        require(len in 16..(16 * 1024 * 1024)) { "Invalid frame size" }
        val ciphertext = ByteArray(len); input.readFully(ciphertext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(byteArrayOf(type))
        return Pair(type, cipher.doFinal(ciphertext))
    }

    companion object {
        const val FRAME_JSON_CONTROL: Byte = 0x01
        const val FRAME_FILE_CHUNK: Byte = 0x02
        const val FRAME_FILE_EOF: Byte = 0x03
        const val FRAME_CANCEL: Byte = 0x04
    }
}
