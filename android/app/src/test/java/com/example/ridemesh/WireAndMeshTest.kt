package com.example.ridemesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class WireAndMeshTest {
    private val group = ByteArray(16) { it.toByte() }
    private val silence = ByteArray(160) { 0xff.toByte() }

    @Test fun crossPlatformPacketVectorAndTamperRejection() {
        val origin = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
        val mediaKey = Wire.mediaKey(group)
        assertEquals("BE45CB26", Wire.roomId(group))
        val packet = Wire.encodeAudio(mediaKey, origin, 0x01020304, 4, silence)
        assertEquals(195, packet.size)
        assertEquals(
            "27EB906E0B6203FED9EF4B9C4CB25FDE6012167F3FCDB6A699FD15D95645513E",
            MessageDigest.getInstance("SHA-256").digest(packet).toHex()
        )
        assertTrue(Wire.decodeAudio(mediaKey, packet)!!.audio.contentEquals(silence))
        assertEquals(3, Wire.decodeAudio(mediaKey, Wire.withLowerTtl(packet))!!.ttl)
        assertNull(Wire.decodeAudio(mediaKey, packet.copyOf().also { it[25] = (it[25].toInt() xor 1).toByte() }))
    }

    @Test fun threePhonesRelayPartitionAndRejoin() {
        data class Delivery(val from: String, val to: String, val data: ByteArray)
        val queue = ArrayDeque<Delivery>()
        val heard = mutableListOf<String>()
        val nodes = mutableMapOf<String, MeshRouter>()
        fun node(name: String, index: Byte): MeshRouter = MeshRouter(
            group, ByteArray(8) { index },
            { to, bytes -> queue.add(Delivery(name, to, bytes)) },
            { _, _ -> heard.add(name) }, { _ -> }
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
        nodes["A"]!!.sendLocal(silence)
        deliver()
        assertEquals(listOf("B", "C"), heard)
        heard.clear()
        nodes["B"]!!.disconnected("C")
        nodes["C"]!!.disconnected("B")
        nodes["A"]!!.sendLocal(silence)
        deliver()
        assertEquals(listOf("B"), heard)
        heard.clear()
        link("B", "C", "9012")
        nodes["A"]!!.sendLocal(silence)
        deliver()
        assertEquals(listOf("B", "C"), heard)
    }
}
