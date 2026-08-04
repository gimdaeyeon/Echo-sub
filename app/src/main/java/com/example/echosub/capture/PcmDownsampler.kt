package com.example.echosub.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 44.1kHz 스테레오 16bit PCM 청크를 16kHz 모노 16bit PCM으로 변환하는 스트리밍 리샘플러.
 *
 * Phase 2에서 STT 입력 파이프에 그대로 재사용하기 위해 순수 변환 로직만 담당한다
 * (녹음/서비스 코드에 대한 의존이 없음).
 *
 * 청크 단위로 [process]를 반복 호출해도 청크 경계에서 끊김/클릭이 생기지 않도록
 * 리샘플 위상(fraction)과 아직 소비하지 못한 입력 샘플(leftover)을 내부에 보존한다.
 * 인스턴스는 하나의 연속된 스트림에 대해서만 재사용해야 한다 (스트림이 바뀌면 새로 생성).
 */
class PcmDownsampler(
    private val inputSampleRate: Int = 44_100,
    private val outputSampleRate: Int = 16_000,
) {
    private val step: Double = inputSampleRate.toDouble() / outputSampleRate.toDouble()

    private var leftoverMono: ShortArray = ShortArray(0)
    private var phase: Double = 0.0

    /**
     * @param input 스테레오 16bit LE interleaved PCM 버퍼
     * @param length 유효 바이트 길이 (4의 배수여야 함: 2채널 * 2바이트)
     * @return 16kHz 모노 16bit LE PCM 바이트 배열 (스트림 상태에 따라 길이가 매 호출 다름, 0바이트일 수도 있음)
     */
    fun process(input: ByteArray, length: Int): ByteArray {
        val usableLength = length - (length % 4)
        val monoSampleCount = usableLength / 4
        val mono = ShortArray(monoSampleCount)

        val bb = ByteBuffer.wrap(input, 0, usableLength).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until monoSampleCount) {
            val left = bb.short.toInt()
            val right = bb.short.toInt()
            mono[i] = ((left + right) / 2).toShort()
        }

        return resample(mono)
    }

    private fun resample(newSamples: ShortArray): ByteArray {
        val combined = ShortArray(leftoverMono.size + newSamples.size)
        System.arraycopy(leftoverMono, 0, combined, 0, leftoverMono.size)
        System.arraycopy(newSamples, 0, combined, leftoverMono.size, newSamples.size)

        if (combined.size < 2) {
            leftoverMono = combined
            return ByteArray(0)
        }

        val output = ArrayList<Short>(combined.size) // upper bound estimate
        var pos = phase

        while (true) {
            val idx = pos.toInt()
            if (idx + 1 >= combined.size) break

            val frac = pos - idx
            val s0 = combined[idx].toDouble()
            val s1 = combined[idx + 1].toDouble()
            val interpolated = s0 + (s1 - s0) * frac
            output.add(interpolated.toInt().toShort())

            pos += step
        }

        val newLeftoverStart = pos.toInt().coerceAtMost(combined.size)
        leftoverMono = combined.copyOfRange(newLeftoverStart, combined.size)
        phase = pos - newLeftoverStart

        val outBuf = ByteBuffer.allocate(output.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        output.forEach { outBuf.putShort(it) }
        return outBuf.array()
    }
}
