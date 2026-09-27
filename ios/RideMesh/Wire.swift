import Foundation
import CryptoKit
import Security

struct AudioPacket {
    let origin: Data
    let sequence: UInt32
    let ttl: UInt8
    let audio: Data
    let raw: Data
    var id: String { origin.hex + ":\(sequence)" }
}

enum Wire {
    static let serviceID = "com.example.ridemesh"
    static let maxTTL: UInt8 = 4

    static func randomBytes(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let result = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        precondition(result == errSecSuccess)
        return Data(bytes)
    }

    static func parseKey(_ text: String) -> Data? {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard clean.count == 32, clean.allSatisfy({ $0.isHexDigit }) else { return nil }
        var output = [UInt8]()
        let chars = Array(clean)
        for index in stride(from: 0, to: 32, by: 2) {
            guard let byte = UInt8(String(chars[index...index + 1]), radix: 16) else { return nil }
            output.append(byte)
        }
        return Data(output)
    }

    static func roomID(_ groupKey: Data) -> String {
        Data(SHA256.hash(data: groupKey).prefix(4)).hex
    }

    static func mediaKey(_ groupKey: Data) -> SymmetricKey {
        SymmetricKey(data: Data(SHA256.hash(data: groupKey + Data("RideMesh-media-v1".utf8))))
    }

    static func proof(_ key: SymmetricKey, code: String) -> Data {
        let input = Data("RideMesh-link-v1:\(code)".utf8)
        let mac = Data(HMAC<SHA256>.authenticationCode(for: input, using: key))
        return Data([0x52, 0x4d, 1, 1]) + mac
    }

    static func isProof(_ data: Data) -> Bool {
        let bytes = Array(data)
        return bytes.count == 36 && bytes[0...3].elementsEqual([0x52, 0x4d, 1, 1])
    }

    static func validProof(_ data: Data, key: SymmetricKey, code: String) -> Bool {
        guard isProof(data) else { return false }
        let expected = Array(proof(key, code))
        let actual = Array(data)
        var difference: UInt8 = 0
        for i in 0..<expected.count { difference |= expected[i] ^ actual[i] }
        return difference == 0
    }

    static func encodeAudio(_ key: SymmetricKey, origin: Data, sequence: UInt32,
                            ttl: UInt8, audio: Data) throws -> Data {
        precondition(origin.count == 8 && ttl <= maxTTL && audio.count == 160)
        var aad = Data([0x52, 0x4d, 1, 2]) + origin
        aad.append(contentsOf: sequence.bigEndianBytes)
        let nonce = try AES.GCM.Nonce(data: origin + Data(sequence.bigEndianBytes))
        let box = try AES.GCM.seal(audio, using: key, nonce: nonce, authenticating: aad)
        let ciphertext = box.ciphertext + box.tag
        var packet = aad
        packet.append(ttl)
        packet.append(UInt8((ciphertext.count >> 8) & 0xff))
        packet.append(UInt8(ciphertext.count & 0xff))
        packet.append(ciphertext)
        return packet
    }

    static func decodeAudio(_ key: SymmetricKey, data: Data) -> AudioPacket? {
        let bytes = Array(data)
        guard bytes.count == 195, bytes[0...3].elementsEqual([0x52, 0x4d, 1, 2]),
              bytes[16] <= maxTTL,
              ((Int(bytes[17]) << 8) | Int(bytes[18])) == 176 else { return nil }
        let origin = Data(bytes[4..<12])
        let sequence = UInt32(bytes[12]) << 24 | UInt32(bytes[13]) << 16 |
            UInt32(bytes[14]) << 8 | UInt32(bytes[15])
        do {
            let nonce = try AES.GCM.Nonce(data: origin + Data(sequence.bigEndianBytes))
            let box = try AES.GCM.SealedBox(nonce: nonce,
                                           ciphertext: Data(bytes[19..<179]),
                                           tag: Data(bytes[179..<195]))
            let audio = try AES.GCM.open(box, using: key, authenticating: Data(bytes[0..<16]))
            return AudioPacket(origin: origin, sequence: sequence, ttl: bytes[16], audio: audio, raw: data)
        } catch { return nil }
    }

    static func lowerTTL(_ data: Data) -> Data {
        var bytes = Array(data)
        bytes[16] -= 1
        return Data(bytes)
    }
}

extension Data {
    var hex: String { map { String(format: "%02X", $0) }.joined() }
}

private extension UInt32 {
    var bigEndianBytes: [UInt8] {
        [UInt8((self >> 24) & 0xff), UInt8((self >> 16) & 0xff),
         UInt8((self >> 8) & 0xff), UInt8(self & 0xff)]
    }
}
