package com.example.echosub.capture

import kotlin.math.sqrt

/**
 * 16bit LE PCM 버퍼의 정규화 RMS 레벨 (0f=무음 ~ 1f=풀스케일).
 *
 * "소리가 들어오고 있는지"를 파일을 열어보기 전에 즉시 보여주기 위한 값이라
 * 정밀할 필요는 없다 — 채널 구분 없이 전체 샘플을 하나의 신호로 취급한다.
 */
fun computeRms16BitLe(buffer: ByteArray, length: Int): Float {
    if (length < 2) return 0f
    var sumSquares = 0.0
    var count = 0
    var i = 0
    while (i + 1 < length) {
        val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
        sumSquares += sample.toDouble() * sample.toDouble()
        count++
        i += 2
    }
    if (count == 0) return 0f
    val rms = sqrt(sumSquares / count)
    return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
}
