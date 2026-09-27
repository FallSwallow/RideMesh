package com.example.ridemesh

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class WireAndMeshTest {
    private val group = ByteArray(16) { it.toByte() }
    private val silence = ByteArray(160) { 0xff.toByte() }

    @Test fun groupKeyQrRoundTrip() {
        val payload = group.toHex()
        val size = 256
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { index ->
            if (matrix[index % size, index / size]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))
        val decoded = MultiFormatReader().decode(image).text
        assertEquals(payload, decoded)
        assertTrue(Wire.parseKey(decoded)!!.contentEquals(group))
    }

    @Test fun suggestedNameUsesDeviceOrFourDigitFallback() {
        assertEquals("騎士手機", Wire.suggestedName(" 騎士手機 ", 42))
        assertEquals("USER0042", Wire.suggestedName(null, 42))
        assertEquals("USER0000", Wire.suggestedName("  ", 0))
        assertEquals("USER9999", Wire.suggestedName("a".repeat(21), 9999))
    }

    @Test fun crossPlatformPacketVectorAndTamperRejection() {
        val origin = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
        val mediaKey = Wire.mediaKey(group)
        assertEquals("BE45CB26", Wire.roomId(group))
        val packet = Wire.encodeAudio(mediaKey, origin, 0x01020304, 4, silence)
        assertEquals(195, packet.size)
        assertEquals(
            "53706664D2B481B499869D35C0DC94A787F13D78EA4BDDE1F0629FAAF4492E09",
            MessageDigest.getInstance("SHA-256").digest(packet).toHex()
        )
        assertTrue(Wire.decodeAudio(mediaKey, packet)!!.audio.contentEquals(silence))
        assertEquals(2, packet[2].toInt())
        assertEquals(3, Wire.decodeAudio(mediaKey, Wire.withLowerTtl(packet))!!.ttl)
        assertNull(Wire.decodeAudio(mediaKey, packet.copyOf().also { it[2] = 1 }))
        assertNull(Wire.decodeAudio(mediaKey, packet.copyOf().also { it[25] = (it[25].toInt() xor 1).toByte() }))
        val proof = Wire.proof(mediaKey, "1234")
        assertEquals("524D020134D03C0C27359CC3038986F56964989E8666518A99C4E89B1217E5C463315D7F", proof.toHex())
        assertTrue(Wire.validProof(proof, mediaKey, "1234"))
        assertTrue(!Wire.validProof(proof.copyOf().also { it[2] = 1 }, mediaKey, "1234"))
        val presenceKey = Wire.presenceKey(group)
        val presence = Wire.encodePresence(presenceKey, origin, 7, 4, "騎士甲")
        assertEquals("騎士甲", Wire.decodePresence(presenceKey, presence)?.name)
        assertEquals(3, Wire.decodePresence(presenceKey, Wire.withLowerTtl(presence))?.ttl)
        assertNull(Wire.decodePresence(presenceKey, presence.copyOf().also { it[2] = 1 }))
        assertNull(Wire.decodePresence(presenceKey, presence.copyOf().also { it[25] = (it[25].toInt() xor 1).toByte() }))
    }

    @Test fun threePhonesRelayPartitionAndRejoin() {
        data class Delivery(val from: String, val to: String, val data: ByteArray)
        val queue = ArrayDeque<Delivery>()
        val heard = mutableListOf<String>()
        val nodes = mutableMapOf<String, MeshRouter>()
        var now = 0L
        fun node(name: String, index: Byte): MeshRouter = MeshRouter(
            group, ByteArray(8) { index }, name,
            { to, bytes -> queue.add(Delivery(name, to, bytes)) },
            { _, _ -> heard.add(name) }, { _ -> }, { _ -> }, { now }
        )
        nodes["A"] = node("A", 1)
        nodes["B"] = node("B", 2)
        nodes["C"] = node("C", 3)
        fun deliver() {
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                nodes[item.to]!!.receive(item.from, item.data)
            }
        }
        fun link(a: String, b: String, code: String) {
            nodes[a]!!.connected(b, code)
            nodes[b]!!.connected(a, code)
            deliver()
        }
        link("A", "B", "1234")
        link("B", "C", "5678")
        nodes.values.forEach(MeshRouter::sendPresence)
        deliver()
        assertEquals(setOf("A", "B", "C"), nodes["A"]!!.memberSnapshot().map { it.name }.toSet())
        nodes["A"]!!.sendLocal(silence)
        deliver()
        assertEquals(listOf("B", "C"), heard)
        heard.clear()
        nodes["B"]!!.disconnected("C")
        nodes["C"]!!.disconnected("B")
        now = 16_000
        nodes["A"]!!.sendPresence()
        nodes["B"]!!.sendPresence()
        deliver()
        nodes.values.forEach(MeshRouter::expireMembers)
        assertEquals(setOf("A", "B"), nodes["A"]!!.memberSnapshot().map { it.name }.toSet())
        assertEquals(listOf("C"), nodes["C"]!!.memberSnapshot().map { it.name })
        nodes["A"]!!.sendLocal(silence)
        deliver()
        assertEquals(listOf("B"), heard)
        heard.clear()
        link("B", "C", "9012")
        nodes.values.forEach(MeshRouter::sendPresence)
        deliver()
        assertEquals(setOf("A", "B", "C"), nodes["C"]!!.memberSnapshot().map { it.name }.toSet())
        nodes["A"]!!.sendLocal(silence)
        deliver()
        assertEquals(listOf("B", "C"), heard)
    }
}
