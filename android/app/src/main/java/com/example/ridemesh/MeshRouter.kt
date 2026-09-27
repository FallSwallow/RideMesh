package com.example.ridemesh

import java.util.LinkedHashMap

data class ChannelMember(val id: String, val name: String, val isSelf: Boolean)

internal class MeshRouter(
    groupKey: ByteArray,
    val nodeId: ByteArray,
    private val localName: String,
    private val send: (String, ByteArray) -> Unit,
    private val play: (String, ByteArray) -> Unit,
    private val onPeerCount: (Int) -> Unit,
    private val onMembers: (List<ChannelMember>) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    val roomId = Wire.roomId(groupKey)
    private val key = Wire.mediaKey(groupKey)
    private val presenceKey = Wire.presenceKey(groupKey)
    private val waiting = mutableMapOf<String, String>()
    private val authorized = mutableSetOf<String>()
    private val seen = object : LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 2048
    }
    private var nextSequence = 0L
    private var nextPresenceSequence = 0L
    private data class MemberRecord(val name: String, var lastSeen: Long)
    private val members = mutableMapOf<String, MemberRecord>()

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
                sendPresence()
            }
            return
        }
        if (endpoint !in authorized) return
        if (Wire.isPresence(data)) {
            val packet = Wire.decodePresence(presenceKey, data) ?: return
            if (seen.put(packet.id, true) != null) return
            val id = packet.origin.toHex()
            if (!packet.origin.contentEquals(nodeId)) {
                val previous = members.put(id, MemberRecord(packet.name, nowMillis()))
                if (previous == null || previous.name != packet.name) onMembers(memberSnapshot())
            }
            if (packet.ttl > 0) {
                val forwarded = Wire.withLowerTtl(data)
                authorized.filter { it != endpoint }.forEach { send(it, forwarded) }
            }
            return
        }
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

    @Synchronized fun sendPresence() {
        if (nextPresenceSequence > 0xffff_ffffL) return
        val sequence = nextPresenceSequence++
        val packet = Wire.encodePresence(presenceKey, nodeId, sequence, Wire.MAX_TTL, localName)
        seen["P:${nodeId.toHex()}:$sequence"] = true
        authorized.forEach { send(it, packet) }
    }

    @Synchronized fun expireMembers() {
        val cutoff = nowMillis() - 15_000
        val iterator = members.iterator()
        var changed = false
        while (iterator.hasNext()) {
            if (iterator.next().value.lastSeen < cutoff) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) onMembers(memberSnapshot())
    }

    @Synchronized fun memberSnapshot(): List<ChannelMember> =
        listOf(ChannelMember(nodeId.toHex(), localName, true)) +
            members.map { ChannelMember(it.key, it.value.name, false) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
