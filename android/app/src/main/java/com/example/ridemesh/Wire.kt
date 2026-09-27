package com.example.ridemesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class AudioPacket(
    val origin: ByteArray,
    val sequence: Long,
    val ttl: Int,
    val audio: ByteArray,
    val raw: ByteArray
) {
    val id: String get() = origin.toHex() + ":" + sequence
}

internal data class PresencePacket(
    val origin: ByteArray,
    val sequence: Long,
    val ttl: Int,
    val name: String
) {
    val id: String get() = "P:" + origin.toHex() + ":" + sequence
}

internal object Wire {
    const val SERVICE_ID = "com.example.ridemesh"
    const val MAX_TTL = 4
    private const val HEADER_SIZE = 19
    private val random = SecureRandom()

    fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)

    fun parseKey(hex: String): ByteArray? {
        val clean = hex.trim().uppercase()
        if (!clean.matches(Regex("[0-9A-F]{32}"))) return null
        return ByteArray(16) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun roomId(groupKey: ByteArray): String = sha256(groupKey).copyOfRange(0, 4).toHex()
    fun mediaKey(groupKey: ByteArray): ByteArray = sha256(groupKey + "RideMesh-media-v1".toByteArray())
    fun presenceKey(groupKey: ByteArray): ByteArray = sha256(groupKey + "RideMesh-presence-v1".toByteArray())
    fun nodeId(): ByteArray = randomBytes(8)

    fun normalizeName(input: String): String? {
        val name = input.trim()
        if (name.isEmpty() || name.codePointCount(0, name.length) > 20 ||
            name.toByteArray(Charsets.UTF_8).size > 64 || name.any { it.isISOControl() }) return null
        return name
    }

    fun proof(key: ByteArray, verificationCode: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return byteArrayOf('R'.code.toByte(), 'M'.code.toByte(), 1, 1) +
            mac.doFinal("RideMesh-link-v1:$verificationCode".toByteArray())
    }

    fun isProof(data: ByteArray): Boolean = data.size == 36 && header(data, 1)
    fun validProof(data: ByteArray, key: ByteArray, code: String): Boolean =
        isProof(data) && MessageDigest.isEqual(data, proof(key, code))

    fun encodeAudio(key: ByteArray, origin: ByteArray, sequence: Long, ttl: Int, audio: ByteArray): ByteArray {
        require(origin.size == 8 && sequence in 0..0xffff_ffffL && ttl in 0..MAX_TTL)
        require(audio.size == 160)
        val aad = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .put('R'.code.toByte()).put('M'.code.toByte()).put(1).put(2)
            .put(origin).putInt(sequence.toInt()).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(origin, sequence)))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(audio)
        return ByteBuffer.allocate(HEADER_SIZE + encrypted.size).order(ByteOrder.BIG_ENDIAN)
            .put(aad).put(ttl.toByte()).putShort(encrypted.size.toShort()).put(encrypted).array()
    }

    fun decodeAudio(key: ByteArray, data: ByteArray): AudioPacket? {
        if (data.size < HEADER_SIZE + 16 || !header(data, 2)) return null
        val ttl = data[16].toInt() and 0xff
        val length = ByteBuffer.wrap(data, 17, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
        if (ttl > MAX_TTL || length != data.size - HEADER_SIZE || length != 176) return null
        val origin = data.copyOfRange(4, 12)
        val sequence = ByteBuffer.wrap(data, 12, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(origin, sequence)))
            cipher.updateAAD(data, 0, 16)
            AudioPacket(origin, sequence, ttl, cipher.doFinal(data, HEADER_SIZE, length), data)
        } catch (_: Exception) {
            null
        }
    }

    fun withLowerTtl(data: ByteArray): ByteArray = data.copyOf().also { it[16] = (it[16] - 1).toByte() }

    fun isPresence(data: ByteArray): Boolean = header(data, 3)

    fun encodePresence(key: ByteArray, origin: ByteArray, sequence: Long, ttl: Int, name: String): ByteArray {
        require(origin.size == 8 && sequence in 0..0xffff_ffffL && ttl in 0..MAX_TTL)
        require(normalizeName(name) == name)
        val aad = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .put('R'.code.toByte()).put('M'.code.toByte()).put(1).put(3)
            .put(origin).putInt(sequence.toInt()).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(origin, sequence)))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(name.toByteArray(Charsets.UTF_8))
        return ByteBuffer.allocate(HEADER_SIZE + encrypted.size).order(ByteOrder.BIG_ENDIAN)
            .put(aad).put(ttl.toByte()).putShort(encrypted.size.toShort()).put(encrypted).array()
    }

    fun decodePresence(key: ByteArray, data: ByteArray): PresencePacket? {
        if (data.size !in 36..99 || !isPresence(data)) return null
        val ttl = data[16].toInt() and 0xff
        val length = ByteBuffer.wrap(data, 17, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
        if (ttl > MAX_TTL || length != data.size - HEADER_SIZE || length !in 17..80) return null
        val origin = data.copyOfRange(4, 12)
        val sequence = ByteBuffer.wrap(data, 12, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(origin, sequence)))
            cipher.updateAAD(data, 0, 16)
            val bytes = cipher.doFinal(data, HEADER_SIZE, length)
            val name = String(bytes, Charsets.UTF_8)
            if (!name.toByteArray(Charsets.UTF_8).contentEquals(bytes) || normalizeName(name) != name) null
            else PresencePacket(origin, sequence, ttl, name)
        } catch (_: Exception) {
            null
        }
    }

    private fun nonce(origin: ByteArray, sequence: Long): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).put(origin).putInt(sequence.toInt()).array()

    private fun header(data: ByteArray, type: Int): Boolean = data.size >= 4 &&
        data[0] == 'R'.code.toByte() && data[1] == 'M'.code.toByte() &&
        data[2] == 1.toByte() && data[3] == type.toByte()

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xff) }
