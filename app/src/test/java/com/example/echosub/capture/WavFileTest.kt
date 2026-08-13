package com.example.echosub.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [WavFileWriter]가 쓴 헤더를 [readWavDurationMs]가 거꾸로 읽는 왕복 검증.
 * 기록 탭의 재생 길이 표시가 이 두 코드의 합의에 전적으로 기대고 있다.
 */
class WavFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `쓴 프레임 수만큼의 재생 길이가 읽힌다`() {
        val file = tmp.newFile("out.wav")
        val writer = WavFileWriter(file, sampleRate = 44_100, channelCount = 2)

        // 1.5초 분량: 44100 * 1.5 프레임 * 4바이트(스테레오 16bit)
        val chunk = ByteArray(44_100 * 4) // 1초
        writer.write(chunk, 0, chunk.size)
        writer.write(chunk, 0, chunk.size / 2) // 0.5초
        writer.close()

        assertEquals(1_500L, readWavDurationMs(file))
    }

    @Test
    fun `모노 16kHz에서도 길이 계산이 맞는다`() {
        val file = tmp.newFile("mono.wav")
        val writer = WavFileWriter(file, sampleRate = 16_000, channelCount = 1)

        writer.write(ByteArray(16_000 * 2), 0, 16_000 * 2) // 1초
        writer.close()

        assertEquals(1_000L, readWavDurationMs(file))
    }

    @Test
    fun `close 전에는 헤더가 패치되지 않아 길이가 없다`() {
        val file = tmp.newFile("open.wav")
        val writer = WavFileWriter(file, sampleRate = 44_100, channelCount = 2)
        writer.write(ByteArray(4_410 * 4), 0, 4_410 * 4)

        // 녹음 중인 파일 시나리오 — 기록 탭에서 길이가 안 나오는 것이 정상 동작이다.
        assertNull(readWavDurationMs(file))

        writer.close()
        assertEquals(100L, readWavDurationMs(file))
    }

    @Test
    fun `헤더보다 짧거나 깨진 파일은 null`() {
        val tooShort = tmp.newFile("short.wav").apply { writeBytes(ByteArray(10)) }
        val garbage = tmp.newFile("garbage.wav").apply { writeBytes(ByteArray(100)) }

        assertNull(readWavDurationMs(tooShort))
        assertNull(readWavDurationMs(garbage))
    }
}
