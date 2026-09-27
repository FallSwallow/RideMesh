package com.example.ridemesh

internal object G711 {
    fun encode(samples: ShortArray): ByteArray = ByteArray(samples.size) { i ->
        val sample = samples[i].toInt()
        val sign = if (sample < 0) 0x80 else 0
        val magnitude = kotlin.math.min(kotlin.math.abs(sample), 32635) + 132
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && magnitude and mask == 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = magnitude shr (exponent + 3) and 0x0f
        (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    fun decode(bytes: ByteArray): ShortArray = ShortArray(bytes.size) { i ->
        val code = bytes[i].toInt().inv() and 0xff
        val exponent = code shr 4 and 0x07
        val magnitude = ((code and 0x0f) shl 3) + 132
        val expanded = magnitude shl exponent
        (if (code and 0x80 != 0) 132 - expanded else expanded - 132).toShort()
    }
}
