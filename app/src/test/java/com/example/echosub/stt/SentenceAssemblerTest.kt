package com.example.echosub.stt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SentenceAssembler]의 병합/봉인 동작 검증. 타이머는 코루틴 가상 시간으로 돌리므로
 * 실제 시간 대기 없이 대기 창([SentenceAssembler.MERGE_WINDOW_MS])/하드
 * 상한([SentenceAssembler.MAX_WAIT_MS]) 경로를 그대로 밟는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SentenceAssemblerTest {

    /** 콜백 호출을 순서대로 기록하는 테스트 더블. */
    private class Recorder {
        private var nextId = 1L
        val opened = mutableListOf<String>()
        val extended = mutableListOf<Pair<Long, String>>()
        val sealed = mutableListOf<Pair<Long, String>>()
        val sealedRanges = mutableListOf<Pair<Long, Long>>()

        fun open(text: String, @Suppress("UNUSED_PARAMETER") startMs: Long, @Suppress("UNUSED_PARAMETER") endMs: Long): Long {
            opened += text
            return nextId++
        }

        fun extend(id: Long, mergedText: String, @Suppress("UNUSED_PARAMETER") endMs: Long) {
            extended += id to mergedText
        }

        fun seal(id: Long, text: String, startMs: Long, endMs: Long) {
            sealed += id to text
            sealedRanges += startMs to endMs
        }
    }

    private fun assembler(scope: CoroutineScope, recorder: Recorder) = SentenceAssembler(
        scope = scope,
        onOpened = recorder::open,
        onExtended = recorder::extend,
        onSealed = recorder::seal,
    )

    @Test
    fun `부호로 끝나는 조각은 기다리지 않고 즉시 확정된다`() = runTest {
        val r = Recorder()
        val a = assembler(this, r)

        a.accept("Hello world.", 0, 1_000)

        assertEquals(listOf("Hello world."), r.opened)
        assertEquals(listOf(1L to "Hello world."), r.sealed)
    }

    @Test
    fun `부호를 한 번도 못 본 스트림에서는 병합 대기가 켜지지 않는다`() = runTest {
        val r = Recorder()
        val a = assembler(this, r)

        // 종결 부호 없는 조각 — 부호 미지원 언어 시나리오. 지연 없이 바로 나가야 한다.
        a.accept("종결 부호가 없는 언어의 문장", 0, 1_000)

        assertEquals(1, r.sealed.size)
    }

    @Test
    fun `문장 중간에 끊긴 조각은 다음 조각과 합쳐 한 번에 확정된다`() = runTest {
        val r = Recorder()
        val a = assembler(this, r)

        a.accept("First.", 0, 1_000) // 부호 학습 + 즉시 확정
        a.accept("So what I", 1_000, 2_000) // 문장 중간 — 대기
        assertEquals(1, r.sealed.size)

        a.accept("want is this.", 2_000, 3_000) // 합쳐서 문장이 닫힘 — 즉시 확정

        assertEquals(2, r.sealed.size)
        assertEquals("So what I want is this.", r.sealed[1].second)
        // 화면 엔트리는 조각이 붙을 때마다 자랐어야 한다.
        assertEquals(listOf(2L to "So what I want is this."), r.extended)
        // 확정 구간은 첫 조각의 시작 ~ 마지막 조각의 끝.
        assertEquals(1_000L to 3_000L, r.sealedRanges[1])
    }

    @Test
    fun `대기 창이 지나면 부호가 없어도 확정된다`() = runTest {
        val r = Recorder()
        val a = assembler(this, r)

        a.accept("First.", 0, 1_000)
        a.accept("dangling fragment", 1_000, 2_000)
        assertEquals(1, r.sealed.size)

        advanceTimeBy(SentenceAssembler.MERGE_WINDOW_MS + 100)

        assertEquals(2, r.sealed.size)
        assertEquals("dangling fragment", r.sealed[1].second)
    }

    @Test
    fun `조각이 계속 이어져 대기 창이 리셋돼도 하드 상한에서 강제 확정된다`() = runTest {
        val r = Recorder()
        val a = assembler(this, r)

        a.accept("First.", 0, 500) // 부호 학습

        // 대기 창보다 짧은 간격으로 부호 없는 조각이 계속 이어지는 강의체 발화 —
        // 소프트 창은 조각마다 리셋되지만 하드 타이머는 문장 시작부터 흐른다.
        // 마지막 조각은 하드 상한 직전에 넣어(4*step=1200ms < MAX_WAIT_MS)
        // 그 조각의 소프트 창(1200+400=1600ms)보다 하드 상한(1400ms)이 먼저 오게 한다.
        val step = SentenceAssembler.MERGE_WINDOW_MS - 100
        a.accept("a", 500, 600)
        advanceTimeBy(step)
        a.accept("b", 600, 700)
        advanceTimeBy(step)
        a.accept("c", 700, 800)
        advanceTimeBy(step)
        a.accept("d", 800, 900)
        advanceTimeBy(step)
        a.accept("e", 900, 1_000)
        assertEquals("아직 하드 상한 전이면 병합이 계속되어야 한다", 1, r.sealed.size)

        // 하드 상한이 지나도록 시간을 흘린다 — 소프트 창은 계속 리셋됐지만 하드 타이머가 끊는다.
        advanceTimeBy(SentenceAssembler.MAX_WAIT_MS)

        assertEquals(2, r.sealed.size)
        assertEquals("a b c d e", r.sealed[1].second)
    }

    @Test
    fun `flush는 대기 중인 문장을 즉시 확정한다`() = runTest {
        val r = Recorder()
        val a = assembler(this, r)

        a.accept("First.", 0, 500)
        a.accept("unfinished tail", 500, 900)
        assertEquals(1, r.sealed.size)

        a.flush()

        assertEquals(2, r.sealed.size)
        assertEquals("unfinished tail", r.sealed[1].second)
    }

    @Test
    fun `상한 길이를 넘은 문장은 부호 없이도 즉시 확정된다`() = runTest {
        val r = Recorder()
        val a = assembler(this, r)

        a.accept("First.", 0, 500)
        a.accept("x".repeat(SentenceAssembler.MAX_CHARS + 10), 500, 900)

        assertEquals(2, r.sealed.size)
    }

    @Test
    fun `빈 텍스트는 무시된다`() = runTest {
        val r = Recorder()
        val a = assembler(this, r)

        a.accept("   ", 0, 100)

        assertTrue(r.opened.isEmpty())
        assertTrue(r.sealed.isEmpty())
    }

    @Test
    fun `닫는 따옴표 뒤의 종결 부호도 문장의 끝으로 인식한다`() = runTest {
        val r = Recorder()
        val a = assembler(this, r)

        a.accept("He said \"Stop.\"", 0, 1_000)

        assertEquals(1, r.sealed.size)
    }
}
