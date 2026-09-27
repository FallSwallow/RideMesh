package com.example.ridemesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

internal class NearbyMesh(
    context: Context,
    private val router: MeshRouter,
    private val status: (String) -> Unit
) {
    private val client = Nearby.getConnectionsClient(context)
    private val handler = Handler(Looper.getMainLooper())
    private val localIdentity = "A${router.nodeId.toHex()}"
    private val localName = "${router.roomId}|${Wire.PROTOCOL_VERSION}|$localIdentity"
    private val authCodes = mutableMapOf<String, String>()
    private val connecting = mutableSetOf<String>()
    private val connected = mutableSetOf<String>()
    private var running = false

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { router.receive(endpointId, it) }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val name = info.endpointName
            if (peerIdentity(name) == null || connected.size >= 4) {
                client.rejectConnection(endpointId)
                return
            }
            authCodes[endpointId] = info.authenticationDigits
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            connecting.remove(endpointId)
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connected.add(endpointId)
                authCodes.remove(endpointId)?.let { router.connected(endpointId, it) }
                handler.postDelayed({
                    if (running && endpointId in connected && !router.isAuthorized(endpointId)) {
                        client.disconnectFromEndpoint(endpointId)
                    }
                }, 5_000)
            } else {
                authCodes.remove(endpointId)
                router.disconnected(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connecting.remove(endpointId)
            connected.remove(endpointId)
            authCodes.remove(endpointId)
            router.disconnected(endpointId)
            if (running) status("鄰居斷線，正在重新搜尋")
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peerName = info.endpointName
            val peerIdentity = peerIdentity(peerName) ?: return
            if (peerIdentity.startsWith('I') || localIdentity >= peerIdentity) return
            if (endpointId in connected || !connecting.add(endpointId) || connected.size >= 4) return
            client.requestConnection(localName, endpointId, lifecycle)
                .addOnFailureListener { connecting.remove(endpointId) }
        }

        override fun onEndpointLost(endpointId: String) = Unit
    }

    fun start() {
        if (running) return
        running = true
        client.startAdvertising(
            localName, Wire.SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        ).addOnFailureListener { status("發布失敗：${it.localizedMessage}") }
        scanCycle()
    }

    private fun scanCycle() {
        if (!running) return
        client.startDiscovery(
            Wire.SERVICE_ID, discovery,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        ).addOnFailureListener { status("搜尋失敗：${it.localizedMessage}") }
        handler.postDelayed({ client.stopDiscovery() }, 4_000)
        handler.postDelayed({ scanCycle() }, 12_000)
    }

    fun send(endpoint: String, data: ByteArray) {
        handler.post {
            if (running && endpoint in connected) client.sendPayload(endpoint, Payload.fromBytes(data))
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        client.stopDiscovery()
        client.stopAdvertising()
        client.stopAllEndpoints()
        connected.toList().forEach(router::disconnected)
        connected.clear()
        connecting.clear()
        authCodes.clear()
    }

    private fun peerIdentity(name: String): String? {
        val parts = name.split('|')
        if (parts.size != 3 || parts[0] != router.roomId ||
            parts[1] != Wire.PROTOCOL_VERSION.toString()) return null
        return parts[2].takeIf { it.matches(Regex("[AI][0-9A-F]{16}")) }
    }
}
