import Foundation
import CryptoKit

final class MeshRouter {
    let nodeID: Data
    let roomID: String
    private let key: SymmetricKey
    private var waiting = [String: String]()
    private var authorized = Set<String>()
    private var seen = Set<String>()
    private var seenOrder = [String]()
    private var sequence: UInt32 = 0
    private let send: (String, Data) -> Void
    private let play: (String, Data) -> Void
    private let onPeerCount: (Int) -> Void

    init(groupKey: Data, nodeID: Data, send: @escaping (String, Data) -> Void,
         play: @escaping (String, Data) -> Void, onPeerCount: @escaping (Int) -> Void) {
        self.nodeID = nodeID
        self.roomID = Wire.roomID(groupKey)
        self.key = Wire.mediaKey(groupKey)
        self.send = send
        self.play = play
        self.onPeerCount = onPeerCount
    }

    func connected(_ endpoint: String, code: String) {
        waiting[endpoint] = code
        send(endpoint, Wire.proof(key, code: code))
    }

    func disconnected(_ endpoint: String) {
        waiting.removeValue(forKey: endpoint)
        if authorized.remove(endpoint) != nil { onPeerCount(authorized.count) }
    }

    func receive(_ endpoint: String, data: Data) {
        if Wire.isProof(data) {
            guard let code = waiting[endpoint] else { return }
            if Wire.validProof(data, key: key, code: code) {
                waiting.removeValue(forKey: endpoint)
                authorized.insert(endpoint)
                onPeerCount(authorized.count)
            }
            return
        }
        guard authorized.contains(endpoint), let packet = Wire.decodeAudio(key, data: data) else { return }
        guard markSeen(packet.id) else { return }
        if packet.origin != nodeID { play(packet.origin.hex, packet.audio) }
        if packet.ttl > 0 {
            let forwarded = Wire.lowerTTL(data)
            for peer in authorized where peer != endpoint { send(peer, forwarded) }
        }
    }

    func sendLocal(_ audio: Data) {
        guard audio.count == 160, sequence < UInt32.max else { return }
        do {
            let packet = try Wire.encodeAudio(key, origin: nodeID, sequence: sequence,
                                              ttl: Wire.maxTTL, audio: audio)
            _ = markSeen("\(nodeID.hex):\(sequence)")
            sequence += 1
            for peer in authorized { send(peer, packet) }
        } catch { return }
    }

    func isAuthorized(_ endpoint: String) -> Bool { authorized.contains(endpoint) }

    private func markSeen(_ id: String) -> Bool {
        guard seen.insert(id).inserted else { return false }
        seenOrder.append(id)
        if seenOrder.count > 2048 { seen.remove(seenOrder.removeFirst()) }
        return true
    }
}
