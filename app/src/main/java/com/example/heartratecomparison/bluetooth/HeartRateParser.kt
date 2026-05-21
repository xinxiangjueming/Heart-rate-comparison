package com.example.heartratecomparison.bluetooth

fun parseHeartRate(data: ByteArray): Int {
    if (data.isEmpty()) return 0
    val flags = data[0].toInt()
    val hrFormat = flags and 0x01
    return if (hrFormat == 1) {
        if (data.size < 3) return 0
        (data[1].toInt() and 0xFF) or (data[2].toInt() shl 8)
    } else {
        if (data.size < 2) return 0
        data[1].toInt() and 0xFF
    }
}
