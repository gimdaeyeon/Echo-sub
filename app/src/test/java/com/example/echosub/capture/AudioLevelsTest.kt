package com.example.echosub.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioLevelsTest {

    private fun pcmBytes(vararg samples: Int): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bb.putShort(it.toShort()) }
        return bb.array()
    }

    @Test
    fun `무음이면 0이다`() {
        val buffer = pcmBytes(0, 0, 0, 0)
        assertEquals(0f, computeRms16BitLe(buffer, buffer.size), 0f)
    }

    @Test
    fun `풀스케일 신호는 1에 수렴한다`() {
        val buffer = pcmBytes(32_767, -32_768, 32_767, -32_768)
        assertEquals(1f, computeRms16BitLe(buffer, buffer.size), 0.001f)
    }

    @Test
    fun `절반 스케일 사각파는 0_5다`() {
        val buffer = pcmBytes(16_384, -16_384, 16_384, -16_384)
        assertEquals(0.5f, computeRms16BitLe(buffer, buffer.size), 0.001f)
    }

    @Test
    fun `유효 길이가 샘플 하나에 못 미치면 0이다`() {
        assertEquals(0f, computeRms16BitLe(ByteArray(1), 1), 0f)
        assertEquals(0f, computeRms16BitLe(ByteArray(0), 0), 0f)
    }
}
