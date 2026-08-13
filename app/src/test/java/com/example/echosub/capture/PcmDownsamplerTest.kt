package com.example.echosub.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PcmDownsamplerTest {

    /** (left, right) 프레임 목록을 16bit LE interleaved 바이트로 편다. */
    private fun stereoBytes(frames: List<Pair<Int, Int>>): ByteArray {
        val bb = ByteBuffer.allocate(frames.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        frames.forEach { (l, r) ->
            bb.putShort(l.toShort())
            bb.putShort(r.toShort())
        }
        return bb.array()
    }

    private fun monoSamples(bytes: ByteArray): ShortArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { bb.short }
    }

    @Test
    fun `스테레오 두 채널의 평균으로 모노를 만든다`() {
        val downsampler = PcmDownsampler()
        // 좌 1000 / 우 3000 상수 신호 — 평균 2000이 나와야 하고, 상수 신호는
        // 선형 보간을 거쳐도 상수여야 한다.
        val input = stereoBytes(List(4_410) { 1_000 to 3_000 })

        val output = monoSamples(downsampler.process(input, input.size))

        assertTrue("출력이 비어 있으면 안 된다", output.isNotEmpty())
        assertTrue(output.all { it == 2_000.toShort() })
    }

    @Test
    fun `출력 샘플 수는 44100 대 16000 비율을 따른다`() {
        val downsampler = PcmDownsampler()
        val frameCount = 44_100 // 1초 분량
        val input = stereoBytes(List(frameCount) { 100 to 100 })

        val outputSamples = downsampler.process(input, input.size).size / 2

        // 위상/경계 처리 때문에 정확히 16000은 아닐 수 있지만 그 근방이어야 한다.
        assertTrue(
            "1초 입력이면 약 16000샘플이 나와야 한다 (실제: $outputSamples)",
            abs(outputSamples - 16_000) <= 2,
        )
    }

    @Test
    fun `청크로 쪼개 넣어도 한 번에 넣은 것과 이어지는 결과가 나온다`() {
        // 청크 경계에서 위상(fraction)과 leftover가 보존되는지 — 리샘플러의 핵심 계약.
        // 경계마다 위상을 재정규화(phase = pos - newLeftoverStart)하며 생기는 부동소수점
        // 오차로 샘플이 ±1 흔들릴 수 있다 — 이는 들리지 않는 수준이고, 계약은
        // "경계에서 끊김/클릭이 없다"이지 비트 단위 동일이 아니다.
        val frames = List(8_820) { i ->
            val s = (sin(2 * PI * 440 * i / 44_100.0) * 10_000).toInt()
            s to s
        }
        val input = stereoBytes(frames)

        val wholeOutput = monoSamples(PcmDownsampler().process(input, input.size))

        val chunked = PcmDownsampler()
        val pieces = mutableListOf<Short>()
        var offset = 0
        val chunkSizes = intArrayOf(400, 1_204, 3_000, 96) // 전부 4의 배수(프레임 경계)
        var chunkIndex = 0
        while (offset < input.size) {
            val size = minOf(chunkSizes[chunkIndex % chunkSizes.size], input.size - offset)
            pieces += monoSamples(chunked.process(input.copyOfRange(offset, offset + size), size)).toList()
            offset += size
            chunkIndex++
        }

        assertEquals(wholeOutput.size, pieces.size)
        wholeOutput.forEachIndexed { i, expected ->
            assertTrue(
                "샘플 $i: 한 번에=$expected, 청크=${pieces[i]} — 차이가 ±1을 넘으면 경계가 끊긴 것",
                abs(expected - pieces[i]) <= 1,
            )
        }
    }

    @Test
    fun `프레임 경계에 못 미치는 자투리 바이트는 버린다`() {
        val downsampler = PcmDownsampler()
        val input = stereoBytes(List(100) { 500 to 500 })

        // 마지막 3바이트를 자투리로 만들어도 예외 없이 처리돼야 한다.
        val output = downsampler.process(input, input.size - 1)

        assertEquals(0, output.size % 2)
    }
}
