package com.example.echosub.caption

/**
 * OCR로 읽은 자막 스냅숏의 연속에서 "새로 나타난 텍스트"만 뽑아내는 차분기.
 *
 * 실시간 자막 창은 스트리밍이 아니라 **화면**이다 — 매 프레임 보이는 것은
 * (1) 이미 내보낸 문장의 일부(아직 안 스크롤됨) + (2) 새 텍스트 + (3) 아직 고쳐질 수
 * 있는 꼬리(음성인식이 마지막 단어를 바꾸는 일이 잦다)가 섞인 전체 창이다.
 * 그대로 매 프레임을 내보내면 같은 문장이 수십 번 번역된다.
 *
 * 동작 규칙 (토큰 = 공백 분리 단어):
 * - **겹침 제거**: 이번 프레임의 앞부분이 지금까지 내보낸 텍스트의 꼬리와 겹치면
 *   그 뒤부터만 새 텍스트다. 비교는 정규화(소문자 + 양끝 문장부호 제거)로 한다 —
 *   자막 창이 나중에 쉼표를 붙이거나 대소문자를 고쳐도 재전송하지 않기 위해서다.
 * - **꼬리 유보**: 새 텍스트의 마지막 [holdBackTokens] 토큰은 아직 바뀔 수 있으므로
 *   내보내지 않고 interim(인식 중)으로만 돌려준다.
 * - **정지 시 방출**: 화면이 [stableFramesToFlush] 프레임 연속 그대로면(말이 멈춤)
 *   유보하던 꼬리까지 전부 내보낸다.
 * - **창이 사라지면**: 유보분을 내보내고, 빈 프레임이 이어지면 겹침 기억을 지운다 —
 *   실시간 자막은 무음이 지속되면 창을 닫고 다음 발화에서 새로 시작하므로,
 *   기억을 지워야 같은 문장이 다시 나와도("네." 같은 짧은 대답) 놓치지 않는다.
 *
 * OCR/번역과 완전히 분리된 순수 로직이라 단독으로 테스트한다.
 * 호출 스레드는 하나라고 가정한다 (ScreenCaptionReader의 캡처 루프).
 */
class CaptionDiffer(
    private val holdBackTokens: Int = HOLD_BACK_TOKENS,
    private val stableFramesToFlush: Int = STABLE_FRAMES_TO_FLUSH,
    private val emptyFramesToReset: Int = EMPTY_FRAMES_TO_RESET,
    private val maxRememberedTokens: Int = MAX_REMEMBERED_TOKENS,
) {
    companion object {
        /** 화면 꼬리에서 아직 고쳐질 수 있다고 보고 유보하는 토큰 수. */
        const val HOLD_BACK_TOKENS = 2

        /** 이 프레임 수만큼 화면이 그대로면 발화가 멈췄다고 보고 유보분을 내보낸다. */
        const val STABLE_FRAMES_TO_FLUSH = 3

        /** 이 프레임 수만큼 화면이 비어 있으면 자막 세션이 끝났다고 보고 겹침 기억을 지운다. */
        const val EMPTY_FRAMES_TO_RESET = 2

        /** 겹침 비교용으로 기억해두는 최근 방출 토큰 수 — 자막 창 몇 화면 분량이면 충분하다. */
        const val MAX_REMEMBERED_TOKENS = 60
    }

    /** [newText]는 이번 프레임에서 확정돼 번역으로 넘겨야 할 새 텍스트 (없으면 null). */
    data class Result(val newText: String?, val interimText: String)

    /** 내보낸 토큰의 꼬리 — 원본 그대로 저장하고 비교할 때만 정규화한다. */
    private val emitted = ArrayDeque<String>()

    /** 직전 프레임에서 유보했던 꼬리 — 창이 사라질 때 이것을 마저 내보낸다. */
    private var pendingTail: List<String> = emptyList()

    private var lastNormalizedFrame: List<String> = emptyList()
    private var stableFrames = 0
    private var emptyFrames = 0

    /** 프레임 하나를 처리한다. [visibleText]는 영역 OCR 결과 전체(빈 문자열 = 자막 창 없음). */
    fun process(visibleText: String): Result {
        val tokens = visibleText.split(WHITESPACE).filter { it.isNotBlank() }
        val normalized = tokens.map(::normalize)

        if (tokens.isEmpty()) {
            emptyFrames++
            stableFrames = 0
            lastNormalizedFrame = emptyList()
            // 창이 닫혔다 — 유보하던 꼬리는 실제로 화면에 있던 말이므로 마저 내보낸다.
            val flushed = takePendingTail()
            if (emptyFrames >= emptyFramesToReset) emitted.clear()
            return Result(flushed, "")
        }
        emptyFrames = 0

        if (normalized == lastNormalizedFrame) stableFrames++ else stableFrames = 0
        lastNormalizedFrame = normalized

        // 이번 프레임의 접두사와 방출 기록의 접미사가 겹치는 최장 길이 — 그 뒤가 새 텍스트다.
        val overlap = longestOverlap(normalized)
        val newTokens = tokens.drop(overlap)

        // 말이 멈췄으면 전부, 아니면 꼬리를 유보하고 내보낸다.
        val emitCount = if (stableFrames >= stableFramesToFlush) {
            newTokens.size
        } else {
            (newTokens.size - holdBackTokens).coerceAtLeast(0)
        }

        val toEmit = newTokens.take(emitCount)
        pendingTail = newTokens.drop(emitCount)

        if (toEmit.isNotEmpty()) remember(toEmit)
        return Result(
            newText = toEmit.takeIf { it.isNotEmpty() }?.joinToString(" "),
            interimText = pendingTail.joinToString(" "),
        )
    }

    /** 세션 종료 시 유보분을 흘리지 않기 위한 마감 — [Result.newText] 규약과 같다. */
    fun flush(): String? = takePendingTail()

    private fun takePendingTail(): String? {
        if (pendingTail.isEmpty()) return null
        val tail = pendingTail
        pendingTail = emptyList()
        remember(tail)
        return tail.joinToString(" ")
    }

    private fun remember(tokens: List<String>) {
        tokens.forEach { emitted.addLast(it) }
        while (emitted.size > maxRememberedTokens) emitted.removeFirst()
    }

    /**
     * `normalizedFrame`의 접두사와 [emitted]의 접미사가 일치하는 최장 토큰 수.
     * n ≤ [maxRememberedTokens]라 단순 비교(O(n²))로 충분하다.
     */
    private fun longestOverlap(normalizedFrame: List<String>): Int {
        val emittedNormalized = emitted.map(::normalize)
        val maxLen = minOf(normalizedFrame.size, emittedNormalized.size)
        for (len in maxLen downTo 1) {
            var match = true
            val emittedStart = emittedNormalized.size - len
            for (i in 0 until len) {
                if (normalizedFrame[i] != emittedNormalized[emittedStart + i]) {
                    match = false
                    break
                }
            }
            if (match) return len
        }
        return 0
    }

    /**
     * 겹침 비교용 정규화 — 소문자로 내리고 양끝의 글자/숫자가 아닌 문자를 걷어낸다.
     * 자막 창이 이미 지나간 단어에 쉼표·마침표를 나중에 붙이는 일이 흔한데,
     * 그때마다 겹침이 깨지면 같은 문장을 다시 내보내게 된다.
     */
    private fun normalize(token: String): String =
        token.lowercase().trim { !it.isLetterOrDigit() }
}

private val WHITESPACE = Regex("\\s+")
