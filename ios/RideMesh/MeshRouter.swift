import Foundation
import CryptoKit

struct ChannelMember: Identifiable, Equatable {
    let id: String
    let name: String
    let isSelf: Bool
}

final class MeshRouter {
    let nodeID: Data
    let roomID: String
    private let key: SymmetricKey
    private let presenceKey: SymmetricKey
    private let localName: String
    private var waiting = [String: String]()
    private var authorized = Set<String>()
    private var seen = Set<String>()
    private var seenOrder = [String]()
    private var sequence: UInt32 = 0
    private var presenceSequence: UInt32 = 0
    private struct MemberRecord { let name: String; var lastSeen: TimeInterval }
    private var members = [String: MemberRecord]()
    private let send: (String, Data) -> Void
    private let play: (String, Data) -> Void
    private let onPeerCount: (Int) -> Void
    private let onMembers: ([ChannelMember]) -> Void
    private let now: () -> TimeInterval

    init(groupKey: Data, nodeID: Data, localName: String, send: @escaping (String, Data) -> Void,
         play: @escaping (String, Data) -> Void, onPeerCount: @escaping (Int) -> Void,
         onMembers: @escaping ([ChannelMember]) -> Void,
         now: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 }) {
        self.nodeID = nodeID
        self.roomID = Wire.roomID(groupKey)
        self.key = Wire.mediaKey(groupKey)
        self.presenceKey = Wire.presenceKey(groupKey)
        self.localName = localName
        self.send = send
        self.play = play
        self.onPeerCount = onPeerCount
        self.onMembers = onMembers
        self.now = now
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
                sendPresence()
            }
            return
        }
        guard authorized.contains(endpoint) else { return }
        if Wire.isPresence(data) {
            guard let packet = Wire.decodePresence(presenceKey, data: data), markSeen(packet.id) else { return }
            if packet.origin != nodeID {
                let id = packet.origin.hex
                let previous = members.updateValue(MemberRecord(name: packet.name, lastSeen: now()), forKey: id)
                if previous == nil || previous?.name != packet.name { onMembers(memberSnapshot()) }
            }
            if packet.ttl > 0 {
                let forwarded = Wire.lowerTTL(data)
                for peer in authorized where peer != endpoint { send(peer, forwarded) }
            }
            return
        }
        guard let packet = Wire.decodeAudio(key, data: data) else { return }
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

    func sendPresence() {
        guard presenceSequence < UInt32.max else { return }
        do {
            let packet = try Wire.encodePresence(presenceKey, origin: nodeID,
                                                 sequence: presenceSequence, ttl: Wire.maxTTL,
                                                 name: localName)
            _ = markSeen("P:\(nodeID.hex):\(presenceSequence)")
            presenceSequence += 1
            for peer in authorized { send(peer, packet) }
        } catch { return }
    }

    func expireMembers() {
        let before = members.count
        let cutoff = now() - 15
        members = members.filter { $0.value.lastSeen >= cutoff }
        if members.count != before { onMembers(memberSnapshot()) }
    }

    func memberSnapshot() -> [ChannelMember] {
        let others = members.map { ChannelMember(id: $0.key, name: $0.value.name, isSelf: false) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        return [ChannelMember(id: nodeID.hex, name: localName, isSelf: true)] + others
    }

    private func markSeen(_ id: String) -> Bool {
        guard seen.insert(id).inserted else { return false }
        seenOrder.append(id)
        if seenOrder.count > 2048 { seen.remove(seenOrder.removeFirst()) }
        return true
    }
}
