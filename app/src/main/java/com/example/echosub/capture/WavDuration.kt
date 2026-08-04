package com.example.echosub.capture

import java.io.File
import java.io.RandomAccessFile

/**
 * [WavFileWriter]가 쓰는 44바이트 표준 PCM 헤더에서 재생 길이(ms)를 읽는다.
 * 녹음 중이라 헤더가 아직 패치되지 않았거나(플레이스홀더 0) 파일이 손상됐으면 null.
 */
fun readWavDurationMs(file: File): Long? = try {
    RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < 44) {
            null
        } else {
            val header = ByteArray(44)
            raf.readFully(header)
            val channelCount = leShort(header, 22)
            val sampleRate = leInt(header, 24)
            val bitsPerSample = leShort(header, 34)
            val dataSize = leInt(header, 40)
            val byteRate = sampleRate * channelCount * (bitsPerSample / 8)
            if (byteRate <= 0 || dataSize <= 0) null else dataSize.toLong() * 1000L / byteRate
        }
    }
} catch (e: Exception) {
    null
}

private fun leShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun leInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
