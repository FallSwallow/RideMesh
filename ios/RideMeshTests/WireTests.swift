import XCTest
import CryptoKit
@testable import RideMesh

final class WireTests: XCTestCase {
    func testAndroidCompatiblePacketAndAuthentication() throws {
        let group = Data((0..<16).map { UInt8($0) })
        let key = Wire.mediaKey(group)
        let origin = Data([0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88])
        let audio = Data(repeating: 0xff, count: 160)
        XCTAssertEqual(Wire.roomID(group), "BE45CB26")
        let packet = try Wire.encodeAudio(key, origin: origin, sequence: 0x01020304, ttl: 4, audio: audio)
        XCTAssertEqual(packet.count, 195)
        XCTAssertEqual(Data(SHA256.hash(data: packet)).hex,
                       "27EB906E0B6203FED9EF4B9C4CB25FDE6012167F3FCDB6A699FD15D95645513E")
        XCTAssertEqual(Wire.decodeAudio(key, data: packet)?.audio, audio)
        XCTAssertEqual(Wire.decodeAudio(key, data: Wire.lowerTTL(packet))?.ttl, 3)
        var tampered = Array(packet)
        tampered[25] ^= 1
        XCTAssertNil(Wire.decodeAudio(key, data: Data(tampered)))
        XCTAssertTrue(Wire.validProof(Wire.proof(key, code: "1234"), key: key, code: "1234"))
        XCTAssertFalse(Wire.validProof(Wire.proof(key, code: "1234"), key: key, code: "5678"))
    }
}
