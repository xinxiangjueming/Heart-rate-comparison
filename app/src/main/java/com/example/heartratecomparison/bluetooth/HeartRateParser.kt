package com.example.heartratecomparison.bluetooth

fun parseHeartRate(data: ByteArray): Int {
    val flags = data[0].toInt()
    val hrFormat = flags and 0x01
    return if (hrFormat == 1) {
        (data[1].toInt() and 0xFF) or (data[2].toInt() shl 8)
    } else {
        data[1].toInt() and 0xFF
    }
}