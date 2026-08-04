package com.example.echosub.capture

import android.media.AudioFormat

/**
 * Phase 1에서 선택 가능한 캡처 포맷.
 *
 * HIFI: 원본 검증용 (44.1kHz 스테레오) — "원본과 동일하게 들리는지" 확인 기준.
 * STT_READY: Phase 2 STT 입력과 동일한 포맷 (16kHz 모노) — AudioRecord가 직접 이 포맷으로 캡처.
 */
enum class CaptureFormat(
    val sampleRate: Int,
    val channelMask: Int,
    val channelCount: Int,
    val label: String,
) {
    HIFI(
        sampleRate = 44_100,
        channelMask = AudioFormat.CHANNEL_IN_STEREO,
        channelCount = 2,
        label = "HiFi 44.1kHz 스테레오",
    ),
    STT_READY(
        sampleRate = 16_000,
        channelMask = AudioFormat.CHANNEL_IN_MONO,
        channelCount = 1,
        label = "STT용 16kHz 모노",
    ),
}

/** WAV/PCM 16bit 기준 프레임(=1 sample * channel 수) 바이트 크기 */
fun CaptureFormat.bytesPerFrame(): Int = 2 * channelCount
