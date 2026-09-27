package com.example.ridemesh

import java.util.LinkedHashMap

internal class MeshRouter(
    groupKey: ByteArray,
    val nodeId: ByteArray,
    private val send: (String, ByteArray) -> Unit,
    private val play: (String, ByteArray) -> Unit,
    private val onPeerCount: (Int) -> Unit
) {
    val roomId = Wire.roomId(groupKey)
    private val key = Wire.mediaKey(groupKey)
    private val waiting = mutableMapOf<String, String>()
    private val authorized = mutableSetOf<String>()
    private val seen = object : LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 2048
    }
    private var nextSequence = 0L

    @Synchronized fun connected(endpoint: String, verificationCode: String) {
        waiting[endpoint] = verificationCode
        send(endpoint, Wire.proof(key, verificationCode))
    }

    @Synchronized fun disconnected(endpoint: String) {
        waiting.remove(endpoint)
        if (authorized.remove(endpoint)) onPeerCount(authorized.size)
    }

    @Synchronized fun receive(endpoint: String, data: ByteArray) {
        if (Wire.isProof(data)) {
            val code = waiting[endpoint] ?: return
            if (Wire.validProof(data, key, code)) {
                waiting.remove(endpoint)
                authorized.add(endpoint)
                onPeerCount(authorized.size)
            }
            return
        }
        if (endpoint !in authorized) return
        val packet = Wire.decodeAudio(key, data) ?: return
        if (seen.put(packet.id, true) != null) return
        if (!packet.origin.contentEquals(nodeId)) play(packet.origin.toHex(), packet.audio)
        if (packet.ttl > 0) {
            val forwarded = Wire.withLowerTtl(data)
            authorized.filter { it != endpoint }.forEach { send(it, forwarded) }
        }
    }

    @Synchronized fun sendLocal(audio: ByteArray) {
        if (audio.size != 160 || nextSequence > 0xffff_ffffL) return
        val packet = Wire.encodeAudio(key, nodeId, nextSequence++, Wire.MAX_TTL, audio)
        seen[nodeId.toHex() + ":" + (nextSequence - 1)] = true
        authorized.forEach { send(it, packet) }
    }

    @Synchronized fun peerCount(): Int = authorized.size

    @Synchronized fun isAuthorized(endpoint: String): Boolean = endpoint in authorized
}
