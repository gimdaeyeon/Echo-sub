package com.example.echosub.util

import org.junit.Assert.assertEquals
import org.junit.Test

class EnumByNameTest {

    private enum class Sample { ALPHA, BETA }

    @Test
    fun `이름이 일치하면 그 값을 돌려준다`() {
        assertEquals(Sample.BETA, enumByName("BETA", Sample.ALPHA))
    }

    @Test
    fun `null이나 모르는 이름이면 기본값이다`() {
        // SharedPreferences에 저장된 이름이 enum 개편으로 사라져도 앱이 죽지 않아야 한다.
        assertEquals(Sample.ALPHA, enumByName(null, Sample.ALPHA))
        assertEquals(Sample.ALPHA, enumByName("GAMMA", Sample.ALPHA))
        assertEquals(Sample.ALPHA, enumByName("beta", Sample.ALPHA)) // 대소문자 구분
    }
}
