import Foundation

enum G711 {
    static func encode(_ samples: [Int16]) -> Data {
        Data(samples.map { sample in
            let value = Int(sample)
            let sign = value < 0 ? 0x80 : 0
            let magnitude = min(abs(value), 32635) + 132
            var exponent = 7
            var mask = 0x4000
            while exponent > 0 && magnitude & mask == 0 {
                exponent -= 1
                mask >>= 1
            }
            let mantissa = (magnitude >> (exponent + 3)) & 0x0f
            return UInt8((sign | (exponent << 4) | mantissa) ^ 0xff)
        })
    }

    static func decode(_ audio: Data) -> [Int16] {
        audio.map { byte in
            let code = Int(byte ^ 0xff)
            let exponent = (code >> 4) & 0x07
            let magnitude = ((code & 0x0f) << 3) + 132
            let expanded = magnitude << exponent
            return Int16(code & 0x80 != 0 ? 132 - expanded : expanded - 132)
        }
    }
}
