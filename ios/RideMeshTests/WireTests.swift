import XCTest
import CryptoKit
@testable import RideMesh

final class WireTests: XCTestCase {
    func testSuggestedNameUsesDeviceOrFourDigitFallback() {
        XCTAssertEqual(Wire.suggestedName(" 騎士手機 ", suffix: 42), "騎士手機")
        XCTAssertEqual(Wire.suggestedName(nil, suffix: 42), "USER0042")
        XCTAssertEqual(Wire.suggestedName(" ", suffix: 0), "USER0000")
        XCTAssertEqual(Wire.suggestedName(String(repeating: "a", count: 21), suffix: 9999), "USER9999")
    }

    func testAndroidCompatiblePacketAndAuthentication() throws {
        let group = Data((0..<16).map { UInt8($0) })
        let key = Wire.mediaKey(group)
        let origin = Data([0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88])
        let audio = Data(repeating: 0xff, count: 160)
        XCTAssertEqual(Wire.roomID(group), "BE45CB26")
        let packet = try Wire.encodeAudio(key, origin: origin, sequence: 0x01020304, ttl: 4, audio: audio)
        XCTAssertEqual(packet.count, 195)
        XCTAssertEqual(Data(SHA256.hash(data: packet)).hex,
                       "53706664D2B481B499869D35C0DC94A787F13D78EA4BDDE1F0629FAAF4492E09")
        XCTAssertEqual(Wire.decodeAudio(key, data: packet)?.audio, audio)
        XCTAssertEqual(Wire.decodeAudio(key, data: Wire.lowerTTL(packet))?.ttl, 3)
        var oldAudio = Array(packet)
        oldAudio[2] = 1
        XCTAssertNil(Wire.decodeAudio(key, data: Data(oldAudio)))
        var tampered = Array(packet)
        tampered[25] ^= 1
        XCTAssertNil(Wire.decodeAudio(key, data: Data(tampered)))
        XCTAssertEqual(Wire.proof(key, code: "1234").hex,
                       "524D020134D03C0C27359CC3038986F56964989E8666518A99C4E89B1217E5C463315D7F")
        XCTAssertTrue(Wire.validProof(Wire.proof(key, code: "1234"), key: key, code: "1234"))
        XCTAssertFalse(Wire.validProof(Wire.proof(key, code: "1234"), key: key, code: "5678"))
        var oldProof = Array(Wire.proof(key, code: "1234"))
        oldProof[2] = 1
        XCTAssertFalse(Wire.validProof(Data(oldProof), key: key, code: "1234"))
        let presenceKey = Wire.presenceKey(group)
        let presence = try Wire.encodePresence(presenceKey, origin: origin, sequence: 7,
                                               ttl: 4, name: "騎士甲")
        XCTAssertEqual(Wire.decodePresence(presenceKey, data: presence)?.name, "騎士甲")
        XCTAssertEqual(Wire.decodePresence(presenceKey, data: Wire.lowerTTL(presence))?.ttl, 3)
        var oldPresence = Array(presence)
        oldPresence[2] = 1
        XCTAssertNil(Wire.decodePresence(presenceKey, data: Data(oldPresence)))
        var badPresence = Array(presence)
        badPresence[25] ^= 1
        XCTAssertNil(Wire.decodePresence(presenceKey, data: Data(badPresence)))
    }
}
