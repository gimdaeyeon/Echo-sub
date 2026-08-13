package com.example.echosub.caption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [CaptionDiffer]의 프레임 차분 동작 검증 — 실시간 자막 창을 OCR로 되읽을 때
 * 같은 문장이 두 번 번역되거나 마지막 말이 사라지지 않는지가 핵심이다.
 */
class CaptionDifferTest {

    private fun differ() = CaptionDiffer()

    @Test
    fun `첫 프레임은 꼬리를 유보하고 나머지를 내보낸다`() {
        val d = differ()

        val result = d.process("this never gets old")

        assertEquals("this never", result.newText)
        assertEquals("gets old", result.interimText)
    }

    @Test
    fun `자막이 자라나면 새 토큰만 내보낸다`() {
        val d = differ()

        d.process("this never gets old")           // "this never" 방출, "gets old" 유보
        val result = d.process("this never gets old for me")

        // 유보됐던 것 + 새로 온 것 중 꼬리 2개를 뺀 만큼만 새로 나간다.
        assertEquals("gets old", result.newText)
        assertEquals("for me", result.interimText)
    }

    @Test
    fun `창이 스크롤돼 앞부분이 사라져도 겹침을 찾는다`() {
        val d = differ()

        d.process("one two three four five six")   // "one two three four" 방출
        // 앞 두 토큰이 창 밖으로 스크롤됐고 새 토큰이 들어왔다.
        val result = d.process("three four five six seven eight")

        assertEquals("five six", result.newText)
        assertEquals("seven eight", result.interimText)
    }

    @Test
    fun `같은 프레임이 반복되면 유보하던 꼬리를 마저 내보낸다`() {
        val d = differ()

        d.process("hello world again")             // "hello" 방출, "world again" 유보
        d.process("hello world again")
        d.process("hello world again")
        val result = d.process("hello world again") // 안정 프레임 임계 도달

        assertEquals("world again", result.newText)
        assertEquals("", result.interimText)
    }

    @Test
    fun `늦게 붙는 문장부호와 대소문자 수정은 재방출을 일으키지 않는다`() {
        val d = differ()

        d.process("i think this works fine")       // "i think this" 방출, "works fine" 유보
        // 자막 창이 이미 방출된 단어들을 "I think, this"로 고쳤다.
        val result = d.process("I think, this works fine now")

        // 겹침이 유지되므로 방출분("i think this")은 다시 나오지 않고,
        // 새 구간(works fine now)에서 꼬리 2개를 뺀 것만 새로 나간다.
        assertEquals("works", result.newText)
        assertEquals("fine now", result.interimText)
    }

    @Test
    fun `창이 사라지면 유보분을 내보내고 기억을 지워 같은 문장을 다시 받는다`() {
        val d = differ()

        d.process("yes.")                          // 토큰 1개 — 전부 유보
        val hidden = d.process("")                 // 창 사라짐 → 유보분 방출
        assertEquals("yes.", hidden.newText)

        d.process("")                              // 빈 프레임 연속 → 기억 리셋

        // 화자가 같은 말을 또 했다 — 리셋됐으므로 다시 잡혀야 한다.
        d.process("yes.")
        val again = d.process("")
        assertEquals("yes.", again.newText)
    }

    @Test
    fun `flush는 유보 중인 꼬리를 돌려준다`() {
        val d = differ()

        d.process("wait for it")                   // "wait" 방출, "for it" 유보

        assertEquals("for it", d.flush())
        assertNull(d.flush())
    }

    @Test
    fun `빈 프레임만 이어지면 아무것도 내보내지 않는다`() {
        val d = differ()

        assertNull(d.process("").newText)
        assertNull(d.process("").newText)
    }
}
