package com.example.echosub.stt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * STT 확정 "조각"을 문장으로 다시 붙이는 조립기.
 *
 * 서버가 주는 확정 결과는 문장이 아니라 발화가 잠깐 끊긴 지점까지의 조각이다.
 * 조각마다 따로 번역하면 반 토막 문장을 문맥 없이 옮기게 되므로, 여기서 문장이
 * 닫힐 때까지 조각을 모아 한 번에 내보낸다.
 *
 * [SttStreamingClient]에서 분리한 이유: gRPC 스트림 관리(채널/세대/재연결)와 문장 병합은
 * 완전히 다른 관심사인데, 병합 쪽이 타이머 셋이 얽힌 가장 미묘한 로직이라 gRPC 없이
 * 단독으로 테스트할 수 있어야 한다. 밖과의 연결은 전부 콜백 주입이다.
 *
 * 동작 규칙:
 * - 조각이 종결 부호로 끝나면 **즉시** 내보낸다 — 대기 비용은 문장 중간에 끊긴 조각만 문다.
 * - 종결 부호를 이 스트림에서 한 번도 못 봤으면 병합을 켜지 않는다 —
 *   `enableAutomaticPunctuation`이 안 되는 언어에서 모든 자막이 [mergeWindowMs]만큼
 *   늦어지기만 하고 얻는 게 없기 때문.
 * - 병합 대기 중이라도 문장 시작 후 [maxWaitMs]가 지나거나 [maxChars]를 넘으면 무조건
 *   내보낸다 — 마침표 없이 쉼표로만 이어지는 발화(강의체)에서 지연이 무한정 커지지 않게.
 *
 * 호출 스레드는 자유다 — 진입점을 [lock]으로 직렬화한다.
 */
class SentenceAssembler(
    private val scope: CoroutineScope,
    private val mergeWindowMs: Long = MERGE_WINDOW_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val maxChars: Int = MAX_CHARS,
    /**
     * 새 문장이 시작될 때 호출. 화면에는 이 시점부터 원문이 보여야 하므로(번역과 별개)
     * 여기서 화면 엔트리를 만들고 그 id를 돌려준다.
     */
    private val onOpened: (text: String, startMs: Long, endMs: Long) -> Long,
    /** 진행 중인 문장에 조각이 더 붙었을 때 — 같은 화면 엔트리를 키운다. */
    private val onExtended: (id: Long, mergedText: String, endMs: Long) -> Unit,
    /** 문장이 확정됐을 때 — 이 시점에 번역/자막 파일 기록으로 넘긴다. */
    private val onSealed: (id: Long, text: String, startMs: Long, endMs: Long) -> Unit,
) {
    companion object {
        /**
         * 문장이 아직 안 끝난 조각을 다음 조각과 합치려고 기다리는 시간.
         * 늘리면 병합 확률(품질)이 오르지만 그만큼 자막이 늦게 뜬다.
         * 600ms에서 내렸다 — 말이 계속 이어지는 영상에서 조각마다 물던 대기가
         * 체감 지연의 큰 몫이었다. 문장이 잘못 쪼개져도 번역기에 앞 문장을 문맥으로
         * 넘기고 있어([com.example.echosub.service.AudioCaptureService]) 품질 손실이 완충된다.
         */
        const val MERGE_WINDOW_MS = 400L

        /**
         * 종결 부호가 끝내 안 나와도 문장 시작 후 이 시간이 지나면 무조건 내보낸다.
         * 2초에서 내렸다 — 쉼 없이 이어지는 발화(강의체)에서 매 문장이 이 상한까지
         * 기다리는 일이 잦아, 상한이 곧 체감 지연이 된다.
         */
        const val MAX_WAIT_MS = 1_400L

        /**
         * 이 길이를 넘으면 문장이 안 닫혀도 내보낸다 (자막 한 장이 지나치게 길어지지 않게).
         * 220자에서 내렸다 — 길게 이어지는 말을 더 일찍 끊어 번역이 더 자주,
         * 더 빨리 도착하게 한다.
         */
        const val MAX_CHARS = 160

        /** 문장이 닫혔다고 볼 종결 부호. */
        private const val SENTENCE_TERMINATORS = ".!?。！？…"

        /** 종결 부호 뒤에 따라붙을 수 있는 닫는 문장부호 — 여기까지 걷어내고 마지막 글자를 본다. */
        private const val TRAILING_MARKS = "\"'”’」』)]»"

        internal fun endsSentence(text: String): Boolean {
            val last = text.trimEnd().trimEnd(*TRAILING_MARKS.toCharArray()).lastOrNull() ?: return false
            return last in SENTENCE_TERMINATORS
        }
    }

    private val lock = Any()

    /** 이 스트림에서 서버가 종결 부호를 붙여준 적이 있는지 — 병합을 켜는 스위치. */
    private var punctuationSeen = false

    // --- 병합 중인 문장 (lock 아래에서만 건드린다) ---
    private var pendingId: Long = -1
    private var pendingText: String = ""
    private var pendingStartMs: Long = 0L
    private var pendingEndMs: Long = 0L

    /** 대기 창 타이머 세대 — 낡은 타이머가 이미 새로 시작된 문장을 끊지 못하게 하는 가드. */
    private var sealGeneration = 0

    /** 확정 조각 하나를 받아 문장이 닫힐 때까지 모으거나, 닫혔으면 바로 내보낸다. */
    fun accept(text: String, startMs: Long, endMs: Long) {
        if (text.isBlank()) return
        if (endsSentence(text)) punctuationSeen = true

        synchronized(lock) {
            if (pendingId >= 0) {
                pendingText = "$pendingText $text"
                pendingEndMs = endMs
                onExtended(pendingId, pendingText, pendingEndMs)
            } else {
                pendingId = onOpened(text, startMs, endMs)
                pendingText = text
                pendingStartMs = startMs
                pendingEndMs = endMs
                scheduleHardSeal(pendingId)
            }

            val shouldWait = punctuationSeen &&
                !endsSentence(pendingText) &&
                pendingText.length < maxChars
            if (shouldWait) scheduleSeal() else sealPending()
        }
    }

    /** 모으던 문장을 즉시 확정한다 — 스트림 종료 시 마지막 문장을 흘리지 않기 위한 것. */
    fun flush() {
        synchronized(lock) { sealPending() }
    }

    /**
     * 문장을 처음 시작했을 때 **딱 한 번** 걸어두는 상한 타이머.
     *
     * [scheduleSeal]의 대기 창은 조각이 새로 올 때마다 다시 잡히므로 문장이 계속
     * 이어지는 한 리셋될 뿐 상한이 없다. 이 타이머는 리셋되지 않고 [maxWaitMs]가
     * 지나면 무조건 끊는다. [id]가 이미 봉인돼 다음 문장으로 넘어갔으면 아무것도 안 한다.
     */
    private fun scheduleHardSeal(id: Long) {
        scope.launch {
            delay(maxWaitMs)
            synchronized(lock) {
                if (pendingId == id) sealPending()
            }
        }
    }

    /**
     * 뒤에 이어질 조각을 잠깐 기다린다. 그 사이 새 조각이 오면 [accept]가 다시
     * 판단하고 이 타이머는 세대 번호로 무효가 된다.
     */
    private fun scheduleSeal() {
        val myGeneration = ++sealGeneration
        scope.launch {
            delay(mergeWindowMs)
            synchronized(lock) {
                if (myGeneration == sealGeneration) sealPending()
            }
        }
    }

    /** 모아둔 문장을 확정해 [onSealed]로 넘긴다. [lock]을 잡고 호출해야 한다. */
    private fun sealPending() {
        val id = pendingId
        if (id < 0) return
        val text = pendingText
        val startMs = pendingStartMs
        val endMs = pendingEndMs

        pendingId = -1
        pendingText = ""
        sealGeneration++ // 대기 중인 타이머 무효화

        onSealed(id, text, startMs, endMs)
    }
}
