package com.example.echosub.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTrackTest {

    @Test
    fun `SRT로 쓰고 되읽으면 시간과 텍스트가 보존된다`() {
        val cues = listOf(
            Cue(startMs = 3_120, endMs = 7_480, sourceText = "Hello there.", translatedText = "안녕하세요."),
            // 번역이 아직 안 붙은 큐 — 한 줄짜리 블록으로 나가고 그렇게 되읽혀야 한다.
            Cue(startMs = 8_000, endMs = 9_500, sourceText = "Still translating", translatedText = null),
            // 1시간 넘는 타임스탬프.
            Cue(startMs = 3_723_004, endMs = 3_725_000, sourceText = "Late line.", translatedText = "늦은 줄."),
        )

        val parsed = parseSrt(cues.toSrt())

        assertEquals(cues, parsed)
    }

    @Test
    fun `형식이 깨진 블록은 건너뛰고 나머지는 살린다`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            멀쩡한 줄
            원문 줄

            2
            이건 시간 줄이 아니다
            본문

            3
            00:00:05,000 --> 00:00:06,000
            두 번째 멀쩡한 줄
        """.trimIndent()

        val parsed = parseSrt(srt)

        assertEquals(2, parsed.size)
        assertEquals("원문 줄", parsed[0].sourceText)
        assertEquals("멀쩡한 줄", parsed[0].translatedText)
        assertEquals("두 번째 멀쩡한 줄", parsed[1].sourceText)
        assertNull(parsed[1].translatedText)
    }

    @Test
    fun `cueAt은 시작은 포함하고 끝은 제외한다`() {
        val cues = listOf(
            Cue(1_000, 2_000, "a"),
            Cue(3_000, 4_000, "b"),
        )

        assertNull(cues.cueAt(999))
        assertEquals("a", cues.cueAt(1_000)?.sourceText)
        assertEquals("a", cues.cueAt(1_999)?.sourceText)
        assertNull(cues.cueAt(2_000)) // 끝시각은 제외 — 다음 큐와 겹치지 않게
        assertNull(cues.cueAt(2_500)) // 무음 구간
        assertEquals("b", cues.cueAt(3_000)?.sourceText)
    }

    @Test
    fun `activeCueIndexAt은 무음 구간에서도 직전 줄을 가리킨다`() {
        val cues = listOf(
            Cue(1_000, 2_000, "a"),
            Cue(3_000, 4_000, "b"),
        )

        assertEquals(-1, cues.activeCueIndexAt(500)) // 아직 시작 전
        assertEquals(0, cues.activeCueIndexAt(1_500))
        assertEquals(0, cues.activeCueIndexAt(2_500)) // 무음 — 하이라이트가 꺼지면 안 된다
        assertEquals(1, cues.activeCueIndexAt(9_999)) // 끝난 뒤에도 마지막 줄 유지
    }

    @Test
    fun `자막 파일 경로는 확장자만 srt로 바뀐다`() {
        val audio = java.io.File("/tmp/captures/capture_20260810_1200_44k_stereo.wav")

        assertEquals("capture_20260810_1200_44k_stereo.srt", subtitleFileFor(audio).name)
        assertEquals(audio.parentFile, subtitleFileFor(audio).parentFile)
    }
}
